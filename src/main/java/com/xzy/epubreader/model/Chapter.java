package com.xzy.epubreader.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节模型：包含标题、纯文本内容和按终端尺寸分页后的页面列表。
 */
public class Chapter {

    private final String title;
    private final String plainText;
    private final List<String> pages;

    public Chapter(String title, String plainText) {
        this.title = (title != null && !title.isBlank()) ? title.trim() : "未命名章节";
        this.plainText = (plainText != null) ? plainText : "";
        this.pages = new ArrayList<>();
    }

    public String getTitle() {
        return title;
    }

    public String getPlainText() {
        return plainText;
    }

    public List<String> getPages() {
        return pages;
    }

    public int getPageCount() {
        return pages.size();
    }

    public String getPage(int index) {
        if (index < 0 || index >= pages.size()) {
            return "";
        }
        return pages.get(index);
    }

    public void addPage(String page) {
        pages.add(page);
    }

    public void clearPages() {
        pages.clear();
    }

    @Override
    public String toString() {
        return String.format("[%d页] %s", pages.size(), title);
    }
}
