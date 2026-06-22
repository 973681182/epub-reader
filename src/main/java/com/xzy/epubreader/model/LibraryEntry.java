package com.xzy.epubreader.model;

/**
 * 书架条目：记录一个 EPUB 文件的基本信息。
 */
public class LibraryEntry {

    private String path;       // EPUB 文件绝对路径
    private String title;      // 书名
    private String author;     // 作者

    public LibraryEntry() {
    }

    public LibraryEntry(String path, String title, String author) {
        this.path = path;
        this.title = (title != null && !title.isBlank()) ? title.trim() : "未知书名";
        this.author = (author != null && !author.isBlank()) ? author.trim() : "未知作者";
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    @Override
    public String toString() {
        return title + " — " + author;
    }
}
