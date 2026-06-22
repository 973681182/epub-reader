package com.xzy.epubreader.parser;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.Chapter;
import nl.siegmann.epublib.domain.Metadata;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.domain.SpineReference;
import nl.siegmann.epublib.domain.TOCReference;
import nl.siegmann.epublib.epub.EpubReader;
import org.jsoup.Jsoup;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * EPUB 文件解析器：打开 EPUB 文件，提取元数据和章节内容。
 */
public class EpubParser {

    /**
     * 解析 EPUB 文件，返回 Book 模型。
     *
     * @param filePath EPUB 文件路径
     * @return 解析后的 Book 对象
     * @throws IOException 读取文件失败时抛出
     */
    public Book parse(String filePath) throws IOException {
        nl.siegmann.epublib.domain.Book epubBook;

        try (FileInputStream fis = new FileInputStream(filePath)) {
            EpubReader reader = new EpubReader();
            epubBook = reader.readEpub(fis);
        }

        Book book = new Book();

        // 提取元数据
        extractMetadata(epubBook, book);

        // 提取章节（优先使用 TOC，回退到 spine）
        List<Chapter> chapters = extractChapters(epubBook);
        for (Chapter ch : chapters) {
            book.addChapter(ch);
        }

        return book;
    }

    /**
     * 从 EPUB 元数据中提取标题和作者。
     */
    private void extractMetadata(nl.siegmann.epublib.domain.Book epubBook, Book book) {
        Metadata metadata = epubBook.getMetadata();

        // 标题
        List<String> titles = metadata.getTitles();
        if (titles != null && !titles.isEmpty()) {
            book.setTitle(titles.get(0));
        }

        // 作者
        List<nl.siegmann.epublib.domain.Author> authors = metadata.getAuthors();
        if (authors != null && !authors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (nl.siegmann.epublib.domain.Author author : authors) {
                if (sb.length() > 0) sb.append(", ");
                String name = author.getFirstname();
                String lastname = author.getLastname();
                if (lastname != null && !lastname.isBlank()) {
                    sb.append(lastname);
                    if (name != null && !name.isBlank()) {
                        sb.append(" ").append(name);
                    }
                } else if (name != null && !name.isBlank()) {
                    sb.append(name);
                }
            }
            if (sb.length() > 0) {
                book.setAuthor(sb.toString());
            }
        }
    }

    /**
     * 提取章节列表：优先从目录（NCX）获取，回退到 spine 顺序。
     */
    private List<Chapter> extractChapters(nl.siegmann.epublib.domain.Book epubBook) {
        List<TOCReference> tocRefs = epubBook.getTableOfContents().getTocReferences();

        if (tocRefs != null && !tocRefs.isEmpty()) {
            // 展平嵌套的 TOC 结构
            List<TOCReference> flatToc = new ArrayList<>();
            flattenToc(tocRefs, flatToc);
            return buildChaptersFromToc(flatToc);
        }

        // 没有 TOC，回退到 spine
        return buildChaptersFromSpine(epubBook);
    }

    /**
     * 递归展平嵌套的 TOC 结构。
     */
    private void flattenToc(List<TOCReference> refs, List<TOCReference> result) {
        for (TOCReference ref : refs) {
            result.add(ref);
            List<TOCReference> children = ref.getChildren();
            if (children != null && !children.isEmpty()) {
                flattenToc(children, result);
            }
        }
    }

    /**
     * 从 TOC 构建章节列表。
     */
    private List<Chapter> buildChaptersFromToc(List<TOCReference> tocRefs) {
        List<Chapter> chapters = new ArrayList<>();

        for (TOCReference ref : tocRefs) {
            Resource resource = ref.getResource();
            if (resource == null) continue;

            String title = ref.getTitle();
            String plainText = resourceToPlainText(resource);

            if (plainText.isBlank()) continue;

            chapters.add(new Chapter(title, plainText));
        }

        return chapters;
    }

    /**
     * 从 spine 顺序构建章节列表（无 TOC 时的回退方案）。
     */
    private List<Chapter> buildChaptersFromSpine(nl.siegmann.epublib.domain.Book epubBook) {
        List<Chapter> chapters = new ArrayList<>();
        List<SpineReference> spineRefs = epubBook.getSpine().getSpineReferences();

        if (spineRefs == null || spineRefs.isEmpty()) {
            return chapters;
        }

        int chapterNum = 1;
        for (SpineReference spineRef : spineRefs) {
            Resource resource = spineRef.getResource();
            if (resource == null) continue;

            String plainText = resourceToPlainText(resource);
            if (plainText.isBlank()) continue;

            String title = "第" + chapterNum + "章";
            chapters.add(new Chapter(title, plainText));
            chapterNum++;
        }

        return chapters;
    }

    /**
     * 将 EPUB 资源（HTML/XHTML）转换为纯文本。
     */
    private String resourceToPlainText(Resource resource) {
        try {
            byte[] data = resource.getData();
            if (data == null || data.length == 0) return "";

            String html = new String(data, StandardCharsets.UTF_8);
            // 用 Jsoup 提取纯文本
            String text = Jsoup.parse(html).text();

            // 合并多余空白行（保留段落分隔）
            return text.replaceAll("\\s*\n\\s*", "\n").trim();
        } catch (IOException e) {
            // 资源读取失败，返回空字符串
            return "";
        }
    }
}
