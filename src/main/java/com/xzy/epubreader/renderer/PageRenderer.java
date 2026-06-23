package com.xzy.epubreader.renderer;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.model.Chapter;

/**
 * 文本分页渲染器：将每章的纯文本按终端尺寸自动换行并分页。
 */
public class PageRenderer {

    private int terminalWidth;
    private int terminalHeight;
    private int pageRows;  // 每页可用行数（总高度 - 状态栏 - 命令条）

    /**
     * 对整本书所有章节分页（初次打开时使用）。
     */
    public void render(Book book, int terminalWidth, int terminalHeight) {
        this.terminalWidth = Math.max(terminalWidth, 40);
        this.terminalHeight = Math.max(terminalHeight, 10);
        this.pageRows = Math.max(this.terminalHeight - 2, 3);

        for (Chapter chapter : book.getChapters()) {
            chapter.clearPages();
            if (!chapter.getPlainText().isEmpty()) {
                paginateChapter(chapter);
            } else {
                chapter.addPage("（空章节）");
            }
        }
    }

    /**
     * 重新分页。
     */
    public void reRender(Book book, int terminalWidth, int terminalHeight) {
        render(book, terminalWidth, terminalHeight);
    }

    /** 确保指定章节已分页（懒渲染） */
    public void ensureChapterRendered(Chapter chapter) {
        if (chapter.getPageCount() == 0 && !chapter.getPlainText().isEmpty()) {
            paginateChapter(chapter);
        }
    }

    /**
     * 对单个章节进行分页。
     */
    private void paginateChapter(Chapter chapter) {
        String text = chapter.getPlainText();

        // 第一步：按段落分行
        String[] paragraphs = text.split("\n");

        // 第二步：对每个段落进行宽度换行（段首加两个全角空格缩进）
        java.util.List<String> allLines = new java.util.ArrayList<>();
        for (String para : paragraphs) {
            if (para.trim().isEmpty()) {
                allLines.add("");  // 保留空行作为段落分隔
                continue;
            }
            // 段首缩进：两个全角空格（U+3000），每个占 2 列，共 4 列
            wordWrapLine("　　" + para.trim(), allLines);
        }

        // 第三步：按 pageRows 切分成页面
        StringBuilder pageBuilder = new StringBuilder();
        int lineCount = 0;

        for (String line : allLines) {
            if (lineCount >= pageRows) {
                chapter.addPage(pageBuilder.toString());
                pageBuilder.setLength(0);
                lineCount = 0;
            }

            if (pageBuilder.length() > 0) {
                pageBuilder.append("\n");
            }
            pageBuilder.append(line);
            lineCount++;
        }

        // 最后一页
        if (pageBuilder.length() > 0) {
            chapter.addPage(pageBuilder.toString());
        }
    }

    /**
     * 对一行文本进行宽度感知的自动换行。
     * 将超出行宽的文本拆分成多行，每行不超过 terminalWidth 显示宽度。
     */
    private void wordWrapLine(String line, java.util.List<String> result) {
        if (line.isEmpty()) {
            result.add("");
            return;
        }

        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            int charWidth = getCharDisplayWidth(c);

            // 如果添加这个字符会超出宽度，先换行
            if (currentWidth + charWidth > terminalWidth && currentWidth > 0) {
                result.add(currentLine.toString());
                currentLine.setLength(0);
                currentWidth = 0;
            }

            currentLine.append(c);
            currentWidth += charWidth;
        }

        // 最后一行
        if (currentLine.length() > 0) {
            result.add(currentLine.toString());
        }
    }

    /**
     * 获取字符在终端中的显示宽度。
     * CJK 字符及其他全角字符占 2 个列宽，其余占 1 个列宽。
     */
    public static int getCharDisplayWidth(char c) {
        // ASCII 控制字符、组合标记等不占宽度
        if (c <= 0x1F || (c >= 0x7F && c <= 0x9F)) {
            return 0;
        }

        // 零宽度字符
        if (c == 0x200B || c == 0x200C || c == 0x200D || c == 0xFEFF) {
            return 0;
        }

        // 全角字符范围
        int type = Character.getType(c);

        // CJK 统一表意文字
        if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HIRAGANA
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.KATAKANA
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_SYLLABLES
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_JAMO
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.BOPOMOFO
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.BOPOMOFO_EXTENDED
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.KANGXI_RADICALS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT) {
            return 2;
        }

        // 中文标点及其他全角字符
        if ((c >= 0xFF01 && c <= 0xFF60)      // 全角 ASCII 和数字
                || (c >= 0xFFE0 && c <= 0xFFE6)    // 全角符号
                || (c >= 0x3000 && c <= 0x303F)    // CJK 标点符号
                || (c >= 0x2000 && c <= 0x206F)    // 通用标点
                || (c >= 0x2E80 && c <= 0x2FDF)    // CJK 部首补充
                || (c >= 0x2FF0 && c <= 0x2FFF)    // 表意文字描述符
                || (c >= 0x3100 && c <= 0x312F)    // 注音符号
                || (c >= 0x31A0 && c <= 0x31BF)    // 注音符号扩展
                || (c >= 0xFE10 && c <= 0xFE1F)    // 竖排形式
                || (c >= 0xFE30 && c <= 0xFE4F)    // CJK 兼容形式
                || (c >= 0xA000 && c <= 0xA4CF)    // 彝文音节
                || (c >= 0x1F100 && c <= 0x1F9FF)) {  // 表情符号等
            return 2;
        }

        return 1;
    }
}
