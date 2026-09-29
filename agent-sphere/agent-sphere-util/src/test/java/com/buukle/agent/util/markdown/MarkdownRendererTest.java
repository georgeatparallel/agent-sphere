package com.buukle.agent.util.markdown;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * config → Markdown 渲染。
 *
 * <p>这里防的是两类回归：一是渲染回退成 JSON（引号花括号会让模型把指令当数据读），
 * 二是空节没有被跳过（prompt 里出现 {@code hardGates: null} 这类噪音）。
 */
class MarkdownRendererTest {

    @Test
    void rendersSectionListWithTitleAndBody() {
        Map<String, Object> section1 = new LinkedHashMap<>();
        section1.put("title", "第一步：打开目标渠道");
        section1.put("body", "顺次打开 boss");
        Map<String, Object> section2 = new LinkedHashMap<>();
        section2.put("title", "硬性门槛（必要条件，任一不满足即不得收藏）");
        section2.put("body", "学历符合其一：bachelor；期望薪资 ≥ 30K。");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sections", List.of(section1, section2));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertTrue(markdown.contains("### 第一步：打开目标渠道"), markdown);
        assertTrue(markdown.contains("顺次打开 boss"), markdown);
        assertTrue(markdown.contains("### 硬性门槛（必要条件，任一不满足即不得收藏）"), markdown);
        // 不得额外输出 sections 这一层标题
        assertFalse(markdown.contains("sections"), markdown);
        // 不得残留 JSON 结构
        assertFalse(markdown.contains("{"), markdown);
        assertFalse(markdown.contains("\"title\""), markdown);
    }

    @Test
    void skipsBlankSections() {
        Map<String, Object> blankBody = new LinkedHashMap<>();
        blankBody.put("title", "岗位画像匹配要求");
        blankBody.put("body", null);
        Map<String, Object> keep = new LinkedHashMap<>();
        keep.put("title", "收藏规则");
        keep.put("body", "① 不达标一律不收藏");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sections", List.of(blankBody, keep));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertFalse(markdown.contains("岗位画像匹配要求"), markdown);
        assertTrue(markdown.contains("### 收藏规则"), markdown);
    }

    @Test
    void fallsBackToGenericRenderingForNonSectionConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("step1", "顺次打开 boss");
        config.put("hardGates", null);
        config.put("nested", Map.of("inner", "值"));
        config.put("items", List.of("a", "b"));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertTrue(markdown.contains("### step1"), markdown);
        assertTrue(markdown.contains("顺次打开 boss"), markdown);
        // null 值整节跳过
        assertFalse(markdown.contains("hardGates"), markdown);
        // 嵌套对象降一级；列表出列表项
        assertTrue(markdown.contains("#### inner"), markdown);
        assertTrue(markdown.contains("- a"), markdown);
    }

    @Test
    void sectionListWithMissingTitleIsNotTreatedAsSections() {
        // title 缺失说明不是小节约定，必须退回通用渲染而不是丢掉内容。
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sections", List.of(Map.of("body", "没有标题")));

        String markdown = MarkdownRenderer.renderConfig(config);

        assertTrue(markdown.contains("### sections"), markdown);
        assertTrue(markdown.contains("没有标题"), markdown);
    }

    @Test
    void emptyOrNullConfigRendersEmpty() {
        assertEquals("", MarkdownRenderer.renderConfig(null));
        assertEquals("", MarkdownRenderer.renderConfig(Map.of()));
    }
}
