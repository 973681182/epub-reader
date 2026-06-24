package com.xzy.epubreader.ui;

import com.xzy.epubreader.model.Book;
import com.xzy.epubreader.storage.ConfigManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令解析与执行器：处理斜杠命令。
 */
public class CommandHandler {

    private final Book book;
    private ConfigManager config;

    // 命令匹配模式
    private static final Pattern CMD_GOTO_PAGE = Pattern.compile("^/goto\\s+(\\d+)$");
    private static final Pattern CMD_GOTO_PCT = Pattern.compile("^/goto\\s+(\\d+(?:\\.\\d+)?)\\s*%$");

    // 执行结果：描述命令做了什么
    private String lastMessage = "";

    public CommandHandler(Book book) {
        this.book = book;
    }

    public void setConfig(ConfigManager config) {
        this.config = config;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    /**
     * 处理用户输入的命令。不以 / 开头的输入会被忽略（返回 NONE）。
     *
     * @param input 用户输入的原始文本
     * @param mode  当前运行模式
     * @return 命令执行结果类型
     */
    public CommandResult handle(String input, Mode mode) {
        if (input == null || input.isBlank()) {
            return CommandResult.NONE;
        }

        String trimmed = input.trim();

        // 只处理斜杠命令
        if (!trimmed.startsWith("/")) {
            lastMessage = "输入 /help 查看可用命令";
            return CommandResult.NONE;
        }

        // 解析命令（按当前模式匹配）
        Command cmd = Command.parse(trimmed, mode);
        if (cmd == null) {
            String cmdName = trimmed.split("\\s+", 2)[0].toLowerCase();
            lastMessage = "未知命令: " + cmdName + "。输入 /help 查看可用命令";
            return CommandResult.NONE;
        }

        // 按空格分割命令和参数
        String[] parts = trimmed.split("\\s+", 2);

        switch (cmd) {
            case READ:
                return handleRead(parts.length > 1 ? parts[1] : "");

            case TOC:
                lastMessage = "共 " + book.getChapterCount() + " 章";
                return CommandResult.SHOW_TOC;

            case GOTO:
                return handleGoto(parts.length > 1 ? parts[1] : "");

            case PROGRESS:
                lastMessage = buildProgressMessage();
                return CommandResult.SHOW_PROGRESS;

            case INFO:
                lastMessage = buildInfoMessage();
                return CommandResult.SHOW_INFO;

            case HELP:
                return CommandResult.SHOW_HELP;

            case SETTINGS:
                return CommandResult.SHOW_SETTINGS;

            case SET:
                if (config == null) {
                    lastMessage = "配置未加载";
                    return CommandResult.NONE;
                }
                String arg = parts.length > 1 ? parts[1].trim() : "";
                if (arg.isEmpty()) {
                    lastMessage = "用法: /set <属性名> <值>  输入 /settings 查看可设置属性";
                    return CommandResult.NONE;
                }
                String[] kv = arg.split("\\s+", 2);
                if (kv.length < 2) {
                    lastMessage = "用法: /set " + kv[0] + " <值>";
                    return CommandResult.NONE;
                }
                String err = config.set(kv[0], kv[1]);
                if (err != null) {
                    lastMessage = err;
                    return CommandResult.NONE;
                }
                lastMessage = "已设置 " + kv[0] + " = " + kv[1];
                return CommandResult.SHOW_SETTINGS; // 刷新设置页面

            case BACK:
                lastMessage = "返回书架";
                return CommandResult.BACK_TO_LIBRARY;

            case QUIT:
            case EXIT:
                lastMessage = "返回书架";
                return CommandResult.BACK_TO_LIBRARY;

            default:
                lastMessage = "该命令在当前模式下不可用: " + cmd.getName();
                return CommandResult.NONE;
        }
    }

    /**
     * 处理 /read 命令。
     */
    private CommandResult handleRead(String arg) {
        if (book.getChapters().isEmpty()) {
            lastMessage = "书籍中没有章节内容";
            return CommandResult.NONE;
        }

        if (arg.isEmpty()) {
            // /read — 从上次位置继续
            lastMessage = "进入阅读模式";
        } else {
            try {
                int chapterNum = Integer.parseInt(arg);
                if (chapterNum < 1 || chapterNum > book.getChapterCount()) {
                    lastMessage = "章节号超出范围 (1-" + book.getChapterCount() + ")";
                    return CommandResult.NONE;
                }
                book.goToChapter(chapterNum - 1);
                lastMessage = "进入阅读模式 — 第" + chapterNum + "章 " + book.getCurrentChapterTitle();
            } catch (NumberFormatException e) {
                lastMessage = "无效的章节号: " + arg;
                return CommandResult.NONE;
            }
        }

        return CommandResult.ENTER_READING;
    }

    /**
     * 处理 /goto 命令。
     */
    private CommandResult handleGoto(String arg) {
        if (book.getTotalPages() == 0) {
            lastMessage = "书籍中没有页面";
            return CommandResult.NONE;
        }

        if (arg.isEmpty()) {
            lastMessage = "用法: /goto <页码> 或 /goto <百分比>%";
            return CommandResult.NONE;
        }

        // 尝试匹配百分比
        Matcher pctMatcher = CMD_GOTO_PCT.matcher("/goto " + arg);
        if (pctMatcher.matches()) {
            double pct = Double.parseDouble(pctMatcher.group(1));
            if (pct < 0 || pct > 100) {
                lastMessage = "百分比应在 0-100 之间";
                return CommandResult.NONE;
            }
            book.goToPercent(pct);
            lastMessage = "已跳转到 " + pct + "% — 第" + (book.getCurrentChapter() + 1) + "章 "
                    + book.getCurrentChapterTitle();
            return CommandResult.ENTER_READING;
        }

        // 尝试匹配页数
        Matcher pageMatcher = CMD_GOTO_PAGE.matcher("/goto " + arg);
        if (pageMatcher.matches()) {
            int page = Integer.parseInt(pageMatcher.group(1));
            if (page < 1 || page > book.getTotalPages()) {
                lastMessage = "页码超出范围 (1-" + book.getTotalPages() + ")";
                return CommandResult.NONE;
            }
            book.goToGlobalPage(page - 1);
            lastMessage = "已跳转到第 " + page + " 页 — 第" + (book.getCurrentChapter() + 1) + "章 "
                    + book.getCurrentChapterTitle();
            return CommandResult.ENTER_READING;
        }

        lastMessage = "无效的跳转目标: " + arg + "。用法: /goto <页码> 或 /goto <百分比>%";
        return CommandResult.NONE;
    }

    private String buildProgressMessage() {
        return String.format("第%d章 %s · 章内 %d/%d 页 · 全书 %d/%d 页 · 进度 %.1f%%",
                book.getCurrentChapter() + 1,
                book.getCurrentChapterTitle(),
                book.getCurrentPage() + 1,
                book.getCurrentChapterPageCount(),
                book.getCurrentGlobalPage() + 1,
                book.getTotalPages(),
                book.getProgressPercent());
    }

    private String buildInfoMessage() {
        return String.format("《%s》— %s · %d章 · %d页",
                book.getTitle(), book.getAuthor(),
                book.getChapterCount(), book.getTotalPages());
    }
}
