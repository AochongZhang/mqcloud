package com.sohu.tv.mq.cloud.util;

import java.util.List;

/**
 * Markdown构建工具
 * @author Aochong Zhang
 * @version 1.0
 * @date 2021-11-05 17:10
 */
public class MarkdownBuilder {
    private final StringBuilder stringBuilder;

    public MarkdownBuilder() {
        this.stringBuilder = new StringBuilder();
    }

    public MarkdownBuilder doReturn() {
         this.stringBuilder.append("\r\n");
         return this;
    }

    public MarkdownBuilder title1(String content) {
        this.stringBuilder.append("# ").append(content);
        return this.doReturn();
    }

    public MarkdownBuilder title2(String content) {
        this.stringBuilder.append("## ").append(content);
        return this.doReturn();
    }

    public MarkdownBuilder title3(String content) {
        this.stringBuilder.append("### ").append(content);
        return this.doReturn();
    }

    public MarkdownBuilder title4(String content) {
        this.stringBuilder.append("#### ").append(content);
        return this.doReturn();
    }

    public MarkdownBuilder title5(String content) {
        this.stringBuilder.append("##### ").append(content);
        return this.doReturn();
    }

    public MarkdownBuilder title6(String content) {
        this.stringBuilder.append("###### ").append(content);
        return this.doReturn();
    }

    public MarkdownBuilder bold(String content) {
        this.stringBuilder.append("**").append(content).append("**");
        return this.doReturn();
    }

    public MarkdownBuilder italic(String content) {
        this.stringBuilder.append("*").append(content).append("*");
        return this.doReturn();
    }

    public MarkdownBuilder link(String name, String url) {
        this.stringBuilder.append("[").append(name).append("]").append("(").append(url).append(")");
        return this.doReturn();
    }

    public MarkdownBuilder photo(String url) {
        this.stringBuilder.append("![]").append("(").append(url).append(")");
        return this.doReturn();
    }

    public MarkdownBuilder quote(String content) {
        this.stringBuilder.append("> ").append(content);
        return this.doReturn();
    }

    public MarkdownBuilder line() {
        this.stringBuilder.append("---");
        return this.doReturn();
    }

    public MarkdownBuilder unorderedList(List<String> list) {
        for (String item : list) {
            this.stringBuilder.append("- ").append(item);
            this.doReturn();
        }
        return this;
    }

    public MarkdownBuilder orderedList(List<String> list) {
        for (int i = 0; i < list.size(); i++) {
            this.stringBuilder.append(i + 1).append(". ").append(list.get(i));
            this.doReturn();
        }
        return this;
    }

    public MarkdownBuilder text(String content) {
        this.stringBuilder.append(content);
        return this;
    }

    public MarkdownBuilder font(String content, String colorCode, String size) {
        this.stringBuilder.append("<font color=")
                .append(colorCode)
                .append(" size=")
                .append(size)
                .append(" face='黑体'>")
                .append(content)
                .append("</font>");
        return this;
    }

    public String build() {
        return this.stringBuilder.toString();
    }
}