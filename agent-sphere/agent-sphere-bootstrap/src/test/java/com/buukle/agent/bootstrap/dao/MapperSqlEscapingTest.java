package com.buukle.agent.bootstrap.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：MyBatis 注解 SQL 里「{@code <}」的两种正确写法不能混用。
 *
 * <p>背景：{@code @Select("...")} 只有在 SQL 被 {@code <script>} 包裹时才会走 XML 解析、
 * 才会把 {@code &lt;} 解码成 {@code <}。没包裹时字符串<b>原样</b>发给 JDBC，于是
 * {@code created_at &lt; ?} 到 PG 手里就成了 {@code created_at lt ?} →
 * {@code ERROR: column "lt" does not exist}（真实踩过：SessionCleanupMapper 三处漏包 script）。
 * 反过来，包了 {@code <script>} 却留裸 {@code <}，会在解析期直接炸。
 *
 * <p>这类问题现有单测一律接不到 —— mapper 全 mock，CI 里也没有库。所以用静态扫描兜住，
 * 思路与 {@code DefaultResourceTemplateMigrationTest} 一致（扫文件防复发）。
 */
class MapperSqlEscapingTest {

    /** 注解读取：text block 与单行字符串两种写法都要覆盖。 */
    private static final Pattern TEXT_BLOCK = Pattern.compile(
            "@(Select|Insert|Update|Delete)\\(\\s*\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern SINGLE_LINE = Pattern.compile(
            "@(Select|Insert|Update|Delete)\\(\"((?:[^\"\\\\\\n]|\\\\.)*)\"\\)");

    /**
     * 完整的开闭标签（必须吃掉闭合的 {@code >}）。少了这个 {@code [^>]*}，
     * {@code </script>} 的 {@code >} 会被当成「残留裸尖括号」而让整份文件误报。
     */
    private static final Pattern MYBATIS_TAG = Pattern.compile(
            "</?(script|foreach|if|choose|when|otherwise|trim|where|set|bind|property)\\b[^>]*>",
            Pattern.DOTALL);

    private static final Pattern XML_ENTITY = Pattern.compile("&(lt|gt|amp);");

    @Test
    @DisplayName("注解 SQL 的尖括号写法必须自洽：无 script 不得转义，有 script 必须转义")
    void mapperSqlAngleBracketUsageMustBeConsistent() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path javaFile : sourceFiles()) {
            String source = readString(javaFile);
            collect(source, TEXT_BLOCK, javaFile, violations);
            collect(source, SINGLE_LINE, javaFile, violations);
        }

        assertTrue(violations.isEmpty(),
                () -> "注解 SQL 的 < 写法不一致（详见每条说明）：\n  " + String.join("\n  ", violations));
    }

    private void collect(String source, Pattern pattern, Path file, List<String> violations) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String sql = matcher.group(2);
            int line = (int) source.substring(0, matcher.start()).chars().filter(c -> c == '\n').count() + 1;
            if (sql.contains("<script")) {
                // 方向 2：剥掉所有 MyBatis 标签后仍有裸尖括号 → XML 解析期会炸
                String rest = MYBATIS_TAG.matcher(sql).replaceAll("");
                if (rest.contains("<") || rest.contains(">")) {
                    violations.add(file + ":" + line + " 包了 <script> 但内部有未转义的尖括号："
                            + snippet(rest));
                }
            } else if (XML_ENTITY.matcher(sql).find()) {
                // 方向 1：转义了却没包 script → 实体被原样发给 PG，当成列名
                violations.add(file + ":" + line + " 用了 &lt;/&gt; 却没有 <script> 包裹，"
                        + "实体不会被解码，PG 会把 lt 当列名");
            }
        }
    }

    /**
     * 定位聚合根目录（含全部 agent-sphere-* 模块的 main 源码）。
     *
     * <p>不能简单取 {@code user.dir/../..}：surefire 的 user.dir 是模块目录，
     * 但从 IDE 或聚合根运行时 user.dir 又是上一层，深浅不一。改为向上找锚点 ——
     * 第一个包含 {@code agent-sphere-common} 的目录即根。
     */
    private List<Path> sourceFiles() throws IOException {
        Path repoRoot = findRepoRoot();
        assertTrue(repoRoot != null,
                "定位不到 agent-sphere 源码根（user.dir=" + Paths.get("").toAbsolutePath() + "）。"
                        + "守卫测试必须真的扫到源码，不能静默跳过。");

        // 深度无关地收集所有 src/main/java：子模块是嵌套的
        //（agent-sphere/agent-sphere-instance/agent-sphere-instance-repository/src/main/java），
        // 假设「模块是根目录直接子目录」会只扫到几个扁平模块，漏掉绝大多数 mapper 且不报错。
        List<Path> javaRoots = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(repoRoot)) {
            tree.filter(Files::isDirectory)
                    .filter(p -> p.toString().replace('\\', '/').endsWith("src/main/java"))
                    .forEach(javaRoots::add);
        }
        assertTrue(!javaRoots.isEmpty(), "在 " + repoRoot + " 下没找到任何 src/main/java，守卫失效");

        List<Path> files = new ArrayList<>();
        for (Path javaRoot : javaRoots) {
            collectJava(javaRoot, files);
        }
        // 数量下限：真实约 600 个文件。扫不到这个量说明路径假设又错了，
        // 而「只扫到几个文件」的守卫恰恰是这种 bug 的伪装成通过。
        assertTrue(files.size() >= 200,
                "只扫到 " + files.size() + " 个源文件（预期 200+），路径假设可能有误，守卫形同虚设");
        return files;
    }

    /** 从当前目录向上找含 {@code agent-sphere-common} 的那一层。 */
    private Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("agent-sphere-common"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private void collectJava(Path dir, List<Path> out) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .forEach(out::add);
        }
    }

    private String readString(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String snippet(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > 80 ? flat.substring(0, 80) + "..." : flat;
    }
}