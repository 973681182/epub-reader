package com.xzy.epubreader.parser;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.Chapter;
import nl.siegmann.epublib.domain.Metadata;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.domain.SpineReference;
import nl.siegmann.epublib.domain.TOCReference;
import nl.siegmann.epublib.epub.EpubReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

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
     * 将字符规范化以适配终端等宽字体显示。
     *
     * <p>EM DASH（U+2014）的 glyph 在等宽终端字体中超出单个 cell 宽度，连续两个会重叠。
     * 替换为 BOX DRAWINGS LIGHT HORIZONTAL（U+2500），精确 1 cell 宽，不会溢出。
     * 两个连续的 EM DASH（——）替换为 "── ──"（两段各两个短横线，中间空格隔开），
     * 还原中文破折号"两段独立横线"的视觉效果。
     */
    private static String normalizeForTerminal(String text) {
        // 先处理成对出现的情况（—— / ――），优先级高于单字符替换
        // 两个 EM DASH "——" → "── ──"（4 个短横线 + 中间空格）
        text = text.replace("——", "── ──");
        text = text.replace("――", "── ──");
        // 单个 EM DASH / HORIZONTAL BAR → 两个短横线
        text = text.replace('—', '─');
        text = text.replace('―', '─');
        // EN DASH → ASCII hyphen
        text = text.replace('–', '-');
        return text;
    }

    /**
     * 将 EPUB 资源（HTML/XHTML）转换为纯文本。
     * <p>
     * 手动遍历 body 的 DOM 树，在每个块级元素（&lt;p&gt;、&lt;div&gt;、&lt;h1&gt;~&lt;h6&gt; 等）
     * 之前插入换行符；&lt;br&gt; 也转为换行；&lt;script&gt;/&lt;style&gt; 跳过。
     * Jsoup 的 text() 和 wholeText() 都不能正确保留段落分隔，所以需要自行遍历。
     */
    private String resourceToPlainText(Resource resource) {
        try {
            byte[] data = resource.getData();
            if (data == null || data.length == 0) return "";

            String html = new String(data, StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html);

            // 移除不需要的标签内容
            doc.select("script, style, noscript").remove();

            StringBuilder sb = new StringBuilder();
            doc.body().traverse(new NodeVisitor() {
                public void head(Node node, int depth) {
                    if (node instanceof TextNode) {
                        sb.append(((TextNode) node).getWholeText());
                    } else if (node instanceof Element) {
                        Element el = (Element) node;
                        // <br> 转为换行
                        if ("br".equals(el.normalName())) {
                            sb.append('\n');
                        } else if (el.isBlock() && sb.length() > 0
                                && sb.charAt(sb.length() - 1) != '\n') {
                            // 块级元素前插入换行（span/em/a 等行内元素不插入）
                            sb.append('\n');
                        }
                    }
                }

                public void tail(Node node, int depth) {
                }
            });

            // 规范化空白：去掉行首尾空白/制表符，合并多个连续空行
            String result = sb.toString()
                    .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();

            // 终端字体兼容：部分 Unicode 字符的 glyph 在等宽终端字体中超出单个 cell 宽度，
            // 替换为终端友好的等价字符，避免重叠和溢出。
            result = normalizeForTerminal(result);

            return result;
        } catch (IOException e) {
            // 资源读取失败，返回空字符串
            return "";
        }
    }
}
