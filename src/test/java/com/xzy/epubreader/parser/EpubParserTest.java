package com.xzy.epubreader.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 测试 HTML→纯文本的转换逻辑：块级元素间必须有换行分隔。
 * <p>
 * 此类仅在修改 HTML 解析逻辑时需要手动运行：
 * <pre>mvn test -Dtest=EpubParserTest</pre>
 * 需先删掉 @Ignore 注解或将 ignore 改为 false。
 */
@Ignore("手动运行：修改 HTML 解析逻辑时使用")
public class EpubParserTest {

    private String repr(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 复现 EpubParser.resourceToPlainText 的核心逻辑，验证段落之间有换行。
     */
    private String htmlToPlainText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript").remove();

        StringBuilder sb = new StringBuilder();
        doc.body().traverse(new NodeVisitor() {
            public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    sb.append(((TextNode) node).getWholeText());
                } else if (node instanceof Element) {
                    Element el = (Element) node;
                    if ("br".equals(el.normalName())) {
                        sb.append('\n');
                    } else if (el.isBlock() && sb.length() > 0
                            && sb.charAt(sb.length() - 1) != '\n') {
                        sb.append('\n');
                    }
                }
            }

            public void tail(Node node, int depth) {
            }
        });

        return sb.toString()
                .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    @Test
    public void testParagraphsSeparatedByNewlines() {
        String html = "<html><body>"
            + "<p>First paragraph.</p>"
            + "<p>Second paragraph.</p>"
            + "<p>Third paragraph.</p>"
            + "</body></html>";

        String text = htmlToPlainText(html);
        System.out.println("text: " + repr(text));
        assertEquals("First paragraph.\nSecond paragraph.\nThird paragraph.", text);
    }

    @Test
    public void testHeadContentExcluded() {
        String html = "<html><head><title>Chapter 1</title></head><body>"
            + "<h1>Chapter Title</h1>"
            + "<p>Content here.</p>"
            + "</body></html>";

        String text = htmlToPlainText(html);
        System.out.println("text: " + repr(text));
        assertFalse("Should NOT contain title text", text.contains("Chapter 1"));
        assertEquals("Chapter Title\nContent here.", text);
    }

    @Test
    public void testBrTagsConvertedToNewline() {
        String html = "<html><body>"
            + "<p>Line one<br>Line two<br>Line three</p>"
            + "</body></html>";

        String text = htmlToPlainText(html);
        System.out.println("text: " + repr(text));
        assertEquals("Line one\nLine two\nLine three", text);
    }

    @Test
    public void testInlineElementsDontAddNewlines() {
        String html = "<html><body>"
            + "<p>This is <em>important</em> and <strong>bold</strong> text.</p>"
            + "</body></html>";

        String text = htmlToPlainText(html);
        System.out.println("text: " + repr(text));
        assertEquals("This is important and bold text.", text);
    }

    @Test
    public void testNestedBlockElements() {
        String html = "<html><body>"
            + "<div>"
            + "<p>Inner paragraph 1</p>"
            + "<p>Inner paragraph 2</p>"
            + "</div>"
            + "<p>Outer paragraph</p>"
            + "</body></html>";

        String text = htmlToPlainText(html);
        System.out.println("text: " + repr(text));
        assertEquals("Inner paragraph 1\nInner paragraph 2\nOuter paragraph", text);
    }

    @Test
    public void testEmptyParagraphsHandled() {
        String html = "<html><body>"
            + "<p></p>"
            + "<p>Real text</p>"
            + "<p></p>"
            + "</body></html>";

        String text = htmlToPlainText(html);
        System.out.println("text: " + repr(text));
        assertEquals("Real text", text);
    }
}
