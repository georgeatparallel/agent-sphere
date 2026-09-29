package com.buukle.agent.util.markdown;

import java.util.List;
import java.util.Map;

/**
 * 把结构化配置渲染为 Markdown，供任务 prompt 使用。
 *
 * <p>为什么不再直接塞 JSON：config 里放的是**给模型执行的指令**，不是数据。
 * JSON 的引号与花括号会让模型把它当数据来读，长句规则被包在引号里也更难被当指令遵循；
 * 而 Markdown 的小标题能让「哪些是步骤、哪些是硬性约束」一眼分明。
 *
 * <p>两类渲染：
 * <ol>
 *   <li><b>结构化小节（约定形状）</b>：{@code {"sections":[{"title":"…","body":"…"}, …]}}。
 *       标题由调用方给出（业务域才知道 {@code hardGates} 该叫「硬性门槛」），
 *       渲染为 {@code ### 标题} + 正文，且**不额外输出 sections 这一层标题**。</li>
 *   <li><b>通用结构</b>：其余任意 Map/List/标量，按层级渲染小标题与列表项，
 *       保证其它调用方即使不遵守小节约定也能得到可读文本。</li>
 * </ol>
 *
 * <p>两条刻意的取舍：
 * <ul>
 *   <li>空值（null / 空白）整节跳过 —— 否则会渲染出 {@code hardGates: null} 这种噪音；</li>
 *   <li>**不使用代码块包裹** —— 用 ``` 包裹等于告诉模型「这是数据」，与「这是执行指令」的意图相反。</li>
 * </ul>
 */
public final class MarkdownRenderer {

    /** 小节约定的键名。 */
    private static final String SECTION_KEY = "sections";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_BODY = "body";

    /** 顶层小标题层级：用 ### 留出上层空间（调用方可能还有 ## 级标题）。 */
    private static final int BASE_HEADING_LEVEL = 3;
    private static final int MAX_HEADING_LEVEL = 6;
    private static final char HEADING_MARK = '#';
    private static final String LIST_ITEM_PREFIX = "- ";
    private static final String LINE_SEPARATOR = "\n";

    private MarkdownRenderer() {
    }

    /**
     * 渲染任务 config。遵守小节约定时按小节展开，否则退回通用渲染。
     * 返回内容前后不留多余空行（由调用方决定拼接位置）。
     */
    public static String renderConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "";
        }
        if (config.size() == 1) {
            Object sections = config.get(SECTION_KEY);
            if (sections instanceof List<?> list && isSectionList(list)) {
                return renderSections(list);
            }
        }
        return renderValue(config, BASE_HEADING_LEVEL);
    }

    /** 逐个小节渲染为 {@code ### 标题\n正文}；标题与正文取首尾空白，空节跳过。 */
    private static String renderSections(List<?> sections) {
        StringBuilder sb = new StringBuilder();
        for (Object item : sections) {
            if (!(item instanceof Map<?, ?> section)) {
                continue;
            }
            String title = asText(section.get(FIELD_TITLE));
            Object body = section.get(FIELD_BODY);
            if (title == null || isBlankValue(body)) {
                continue;
            }
            appendBlock(sb, heading(BASE_HEADING_LEVEL) + title);
            appendBlock(sb, renderValue(body, BASE_HEADING_LEVEL + 1));
        }
        return sb.toString();
    }

    /** 通用渲染：Map 逐键出小标题，List 出列表项，标量原样。 */
    private static String renderValue(Object value, int level) {
        if (isBlankValue(value)) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object entryValue = entry.getValue();
                if (isBlankValue(entryValue)) {
                    continue;
                }
                String key = asText(entry.getKey());
                if (key == null) {
                    continue;
                }
                appendBlock(sb, heading(level) + key);
                appendBlock(sb, renderValue(entryValue, level + 1));
            }
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (isBlankValue(item)) {
                    continue;
                }
                appendBlock(sb, LIST_ITEM_PREFIX + asText(item));
            }
            return sb.toString();
        }
        return asText(value);
    }

    /** 追加一个非空块（块之间恰好一个换行）。 */
    private static void appendBlock(StringBuilder sb, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(LINE_SEPARATOR);
        }
        sb.append(block.strip());
    }

    private static String heading(int level) {
        int safeLevel = Math.min(Math.max(level, 1), MAX_HEADING_LEVEL);
        return String.valueOf(HEADING_MARK).repeat(safeLevel) + " ";
    }

    /** 小节约定的判定：非空，且每个元素都是带非空 title 的 Map。 */
    private static boolean isSectionList(List<?> list) {
        if (list.isEmpty()) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || asText(map.get(FIELD_TITLE)) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
