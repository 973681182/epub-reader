package com.xzy.epubreader.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 书籍模型：包含元数据、章节列表和当前阅读位置。
 */
public class Book {

    private String title;
    private String author;
    private final List<Chapter> chapters;
    private int currentChapter;     // 当前章节索引
    private int currentPage;        // 当前章内页码

    public Book() {
        this.title = "未知书名";
        this.author = "未知作者";
        this.chapters = new ArrayList<>();
        this.currentChapter = 0;
        this.currentPage = 0;
    }

    // ========== 元数据 ==========

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = (title != null && !title.isBlank()) ? title.trim() : "未知书名";
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = (author != null && !author.isBlank()) ? author.trim() : "未知作者";
    }

    // ========== 章节 ==========

    public List<Chapter> getChapters() {
        return chapters;
    }

    public void addChapter(Chapter chapter) {
        chapters.add(chapter);
    }

    public int getChapterCount() {
        return chapters.size();
    }

    public Chapter getChapter(int index) {
        if (index < 0 || index >= chapters.size()) {
            return null;
        }
        return chapters.get(index);
    }

    // ========== 当前阅读位置 ==========

    public int getCurrentChapter() {
        return currentChapter;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public Chapter getCurrentChapterObj() {
        return getChapter(currentChapter);
    }

    /**
     * 获取当前章节名
     */
    public String getCurrentChapterTitle() {
        Chapter ch = getCurrentChapterObj();
        return ch != null ? ch.getTitle() : "";
    }

    /**
     * 全书总页数
     */
    public int getTotalPages() {
        return chapters.stream().mapToInt(Chapter::getPageCount).sum();
    }

    /**
     * 当前全局页码（跨章节累计，从0开始）
     */
    public int getCurrentGlobalPage() {
        int global = 0;
        for (int i = 0; i < currentChapter; i++) {
            global += chapters.get(i).getPageCount();
        }
        global += currentPage;
        return global;
    }

    /**
     * 当前章节内总页数
     */
    public int getCurrentChapterPageCount() {
        Chapter ch = getCurrentChapterObj();
        return ch != null ? ch.getPageCount() : 0;
    }

    /**
     * 阅读进度百分比 (0-100)
     */
    public double getProgressPercent() {
        int total = getTotalPages();
        if (total == 0) return 0.0;
        return (double) getCurrentGlobalPage() / total * 100.0;
    }

    // ========== 导航 ==========

    /**
     * 翻到下一页，返回是否成功（到达末尾返回 false）
     */
    public boolean nextPage() {
        if (chapters.isEmpty()) return false;

        Chapter ch = getCurrentChapterObj();
        if (ch == null) return false;

        // 当前章节还有下一页
        if (currentPage + 1 < ch.getPageCount()) {
            currentPage++;
            return true;
        }

        // 尝试进入下一章
        if (currentChapter + 1 < chapters.size()) {
            currentChapter++;
            currentPage = 0;
            return true;
        }

        // 已经是最后一页
        return false;
    }

    /**
     * 翻到上一页，返回是否成功（到达开头返回 false）
     */
    public boolean prevPage() {
        if (chapters.isEmpty()) return false;

        // 当前章节还有上一页
        if (currentPage > 0) {
            currentPage--;
            return true;
        }

        // 尝试进入上一章的最后一页
        if (currentChapter > 0) {
            currentChapter--;
            Chapter prevChapter = chapters.get(currentChapter);
            currentPage = Math.max(0, prevChapter.getPageCount() - 1);
            return true;
        }

        // 已经是第一页
        return false;
    }

    /**
     * 跳转到指定章节的第一页
     */
    public void goToChapter(int index) {
        if (index < 0) index = 0;
        if (index >= chapters.size()) index = chapters.size() - 1;
        if (chapters.isEmpty()) return;
        currentChapter = index;
        currentPage = 0;
    }

    /**
     * 跳转到指定全局页码
     */
    public void goToGlobalPage(int targetPage) {
        if (chapters.isEmpty()) return;
        if (targetPage <= 0) {
            currentChapter = 0;
            currentPage = 0;
            return;
        }

        int accumulated = 0;
        for (int i = 0; i < chapters.size(); i++) {
            int chapterPages = chapters.get(i).getPageCount();
            if (accumulated + chapterPages > targetPage) {
                currentChapter = i;
                currentPage = targetPage - accumulated;
                return;
            }
            accumulated += chapterPages;
        }

        // 超出总页数，定位到最后一页
        currentChapter = chapters.size() - 1;
        Chapter lastChapter = chapters.get(currentChapter);
        currentPage = Math.max(0, lastChapter.getPageCount() - 1);
    }

    /**
     * 按百分比跳转
     */
    public void goToPercent(double percent) {
        int total = getTotalPages();
        if (total == 0) return;
        int targetPage = (int) (total * percent / 100.0);
        goToGlobalPage(targetPage);
    }

    /**
     * 是否有更多页面（未到末尾）
     */
    public boolean hasMorePages() {
        if (chapters.isEmpty()) return false;
        if (currentChapter < chapters.size() - 1) return true;
        Chapter ch = getCurrentChapterObj();
        return ch != null && currentPage < ch.getPageCount() - 1;
    }

    @Override
    public String toString() {
        return String.format("《%s》 %s · %d章 · %d页 · 进度%.1f%%",
                title, author, chapters.size(), getTotalPages(), getProgressPercent());
    }
}
