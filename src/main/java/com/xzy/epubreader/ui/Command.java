package com.xzy.epubreader.ui;

import java.util.*;

/**
 * 命令枚举：每个命令的名称、描述和可用模式。
 * 是命令补全、帮助画面、命令校验的唯一数据源。
 */
public enum Command {

    // ---- 阅读时可用（在阅读页面按 / 打开命令行） ----
    READ("/read", "进入阅读模式，可指定章节号", Mode.READING),
    TOC("/toc", "显示章节目录", Mode.READING),
    GOTO("/goto", "跳转到指定页码或百分比位置", Mode.READING),
    PROGRESS("/progress", "显示详细阅读进度", Mode.READING),
    INFO("/info", "显示书籍元信息", Mode.READING),
    BACK("/back", "返回书架", Mode.READING),

    // ---- 书架时可用（在书架页面按 / 打开命令行） ----
    READ_SHELF("/read", "打开书架中指定序号的书籍", Mode.LIBRARY),
    ADD("/add", "添加 EPUB 文件到书架", Mode.LIBRARY),
    DELETE("/delete", "从书架移除书籍", Mode.LIBRARY),

    // ---- 书架和阅读都可用 ----
    HELP("/help", "显示所有命令帮助", Mode.LIBRARY, Mode.READING),
    QUIT("/quit", "退出程序（书架）/ 返回书架（阅读）", Mode.LIBRARY, Mode.READING),
    EXIT("/exit", "退出程序（书架）/ 返回书架（阅读）", Mode.LIBRARY, Mode.READING);

    private final String name;
    private final String description;
    private final Set<Mode> availableModes;

    Command(String name, String description, Mode... modes) {
        this.name = name;
        this.description = description;
        this.availableModes = EnumSet.copyOf(Arrays.asList(modes));
    }

    /** 命令名，如 "/read" */
    public String getName() {
        return name;
    }

    /** 命令描述，用于帮助和补全提示 */
    public String getDescription() {
        return description;
    }

    /** 返回命令可用的模式集合 */
    public Set<Mode> getAvailableModes() {
        return availableModes;
    }

    /** 命令是否在指定模式下可用 */
    public boolean isAvailableIn(Mode mode) {
        return availableModes.contains(mode);
    }

    /**
     * 根据输入文本和当前模式解析命令。
     *
     * @param input 用户输入的原始文本（如 "/read 5"）
     * @param mode  当前运行模式
     * @return 匹配的命令，无匹配或模式不可用时返回 null
     */
    public static Command parse(String input, Mode mode) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String cmdName = input.trim().split("\\s+", 2)[0].toLowerCase();
        for (Command cmd : values()) {
            if (cmd.name.equals(cmdName) && cmd.isAvailableIn(mode)) {
                return cmd;
            }
        }
        return null;
    }

    /**
     * 根据部分输入匹配命令列表，用于 Tab 补全和命令提示。
     * 只返回当前模式下可用的命令，按匹配度排序，最多 5 条。
     *
     * @param input 用户已输入的文本（必须以 "/" 开头）
     * @param mode  当前运行模式
     * @return 匹配的命令列表
     */
    public static List<Command> match(String input, Mode mode) {
        if (input == null || input.isEmpty() || !input.startsWith("/")) {
            return Collections.emptyList();
        }
        String lower = input.toLowerCase();
        List<Command> matches = new ArrayList<>();
        for (Command cmd : values()) {
            if (cmd.name.startsWith(lower) && cmd.isAvailableIn(mode)) {
                matches.add(cmd);
            }
        }
        // 排序：完全匹配优先，其余按名称字母序
        matches.sort((a, b) -> {
            boolean aExact = a.name.equalsIgnoreCase(input);
            boolean bExact = b.name.equalsIgnoreCase(input);
            if (aExact && !bExact) return -1;
            if (!aExact && bExact) return 1;
            return a.name.compareTo(b.name);
        });
        if (matches.size() > 5) {
            return matches.subList(0, 5);
        }
        return matches;
    }

    /**
     * 返回指定模式下所有可用的命令。
     */
    public static List<Command> forMode(Mode mode) {
        List<Command> result = new ArrayList<>();
        for (Command cmd : values()) {
            if (cmd.isAvailableIn(mode)) {
                result.add(cmd);
            }
        }
        return result;
    }

    /**
     * 返回 input 到 target 的补全后缀。
     * 例如 input="/r", target="/read" → "ead"
     */
    public static String completionSuffix(String input, String target) {
        if (target.startsWith(input) && target.length() > input.length()) {
            return target.substring(input.length());
        }
        return null;
    }
}
