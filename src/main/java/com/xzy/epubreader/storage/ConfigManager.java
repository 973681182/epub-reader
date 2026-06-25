package com.xzy.epubreader.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 配置管理器：从 dataDir/config.jsonc 读取用户配置，提供类型安全的 getter。
 *
 * <p>所有 getter 在字段缺失或类型不匹配时返回默认值，确保文件不存在时程序照常运行。
 * 解析器为手写递归下降，不依赖第三方 JSON 库，与 StorageManager 风格一致。
 */
public class ConfigManager {

    private final Map<String, Object> data;
    private final Path configFile;

    // ==================== 默认值常量 ====================

    private static final boolean DEFAULT_FIRST_LINE_INDENT = true;
    private static final int DEFAULT_BOTTOM_MARGIN = 4;
    private static final String DEFAULT_CURSOR_STYLE = "block";
    private static final String DEFAULT_CURSOR_COLOR = "#c0c0c0";
    private static final String DEFAULT_PROGRESS_BAR_POSITION = "bottom";
    private static final boolean DEFAULT_SHOW_COMMAND_PANEL = true;
    private static final boolean DEFAULT_AUTO_SCAN_BOOKS = true;
    private static final List<String> DEFAULT_SCAN_DIRECTORIES = Collections.singletonList("books");
    private static final boolean DEFAULT_CONFIRM_DELETE = true;
    private static final boolean DEFAULT_CONFIRM_ADD = true;

    private static final Set<String> VALID_CURSOR_STYLES =
            new HashSet<>(Arrays.asList("block", "underline", "bar"));

    private static final Set<String> VALID_PROGRESS_BAR_POSITIONS =
            new HashSet<>(Arrays.asList("top", "bottom", "hidden"));

    /** 进度条位置预设：内部值 → 设置页面显示名 */
    private static final String[] PROGRESS_BAR_POSITION_LABELS = {"上方", "下方", "隐藏"};
    private static final String[] PROGRESS_BAR_POSITION_VALUES = {"top", "bottom", "hidden"};

    /** 光标颜色预设：显示名（设置页面用）和对应的 hex 值 */
    private static final String[] CURSOR_COLOR_LABELS = {
            "亮灰色", "白色", "酸橙绿", "深天蓝", "金色", "番茄红", "热粉红"
    };
    private static final String[] CURSOR_COLOR_VALUES = {
            "#c0c0c0", "#ffffff", "#32cd32", "#00bfff", "#ffd700", "#ff6347", "#ff69b4"
    };

    /** 将显示名或 hex 统一转为 hex；无法识别时返回原值 */
    private static String resolveCursorColor(String name) {
        if (name == null) return null;
        for (int i = 0; i < CURSOR_COLOR_LABELS.length; i++) {
            if (CURSOR_COLOR_LABELS[i].equals(name)) return CURSOR_COLOR_VALUES[i];
        }
        return name; // 可能是 hex，交给 setCursorColor 的 regex 校验
    }

    /** 将 hex 转回中文显示名，未匹配时返回原 hex */
    private static String cursorColorToLabel(String hex) {
        if (hex == null) return DEFAULT_CURSOR_COLOR;
        for (int i = 0; i < CURSOR_COLOR_VALUES.length; i++) {
            if (CURSOR_COLOR_VALUES[i].equalsIgnoreCase(hex)) return CURSOR_COLOR_LABELS[i];
        }
        return hex; // 自定义 hex 值，原样显示
    }

    /** 将进度条位置显示名转为内部值（"上方"→"top" 等）；无法识别时返回默认值 */
    private static String resolveProgressBarPosition(String label) {
        if (label == null) return DEFAULT_PROGRESS_BAR_POSITION;
        for (int i = 0; i < PROGRESS_BAR_POSITION_LABELS.length; i++) {
            if (PROGRESS_BAR_POSITION_LABELS[i].equals(label)) return PROGRESS_BAR_POSITION_VALUES[i];
        }
        // 可能是内部值直接传入（"/set progressBarPosition top"），校验通过则原样返回
        if (VALID_PROGRESS_BAR_POSITIONS.contains(label)) return label;
        return DEFAULT_PROGRESS_BAR_POSITION;
    }

    /** 将进度条位置内部值转为中文显示名 */
    private static String progressBarPositionToLabel(String value) {
        if (value == null) return "下方";
        for (int i = 0; i < PROGRESS_BAR_POSITION_VALUES.length; i++) {
            if (PROGRESS_BAR_POSITION_VALUES[i].equals(value)) return PROGRESS_BAR_POSITION_LABELS[i];
        }
        return "下方";
    }

    // ==================== 按键默认值 ====================

    private static final Set<Integer> DEFAULT_NEXT_PAGE =
            new HashSet<>(Arrays.asList(-11, 32, -21, -22));  // Enter, Space, ArrowDown, ArrowRight
    private static final Set<Integer> DEFAULT_PREV_PAGE =
            new HashSet<>(Arrays.asList(-20, -23));           // ArrowUp, ArrowLeft
    private static final int DEFAULT_EXIT_READING = -10;       // Escape
    private static final int DEFAULT_ACTIVATE_CMD = '/';
    private static final int DEFAULT_LIBRARY_OPEN = -11;       // Enter
    private static final int DEFAULT_LIBRARY_UP = -20;         // ArrowUp
    private static final int DEFAULT_LIBRARY_DOWN = -21;       // ArrowDown
    private static final int DEFAULT_QUIT = 'q';
    private static final int DEFAULT_QUICK_ADD = 'a';
    private static final int DEFAULT_QUICK_DELETE = 'd';

    // ==================== 缓存的按键值 ====================

    private Set<Integer> readingNextPageKeys = DEFAULT_NEXT_PAGE;
    private Set<Integer> readingPrevPageKeys = DEFAULT_PREV_PAGE;
    private int readingExitKey = DEFAULT_EXIT_READING;
    private int readingCommandKey = DEFAULT_ACTIVATE_CMD;
    private int libraryOpenKey = DEFAULT_LIBRARY_OPEN;
    private int librarySelectUpKey = DEFAULT_LIBRARY_UP;
    private int librarySelectDownKey = DEFAULT_LIBRARY_DOWN;
    private int libraryQuitKey = DEFAULT_QUIT;
    private int libraryQuickAddKey = DEFAULT_QUICK_ADD;
    private int libraryQuickDeleteKey = DEFAULT_QUICK_DELETE;
    private int libraryCommandKey = DEFAULT_ACTIVATE_CMD;

    // ==================== 构造 ====================

    /**
     * 从指定数据目录加载配置。文件不存在时使用全部默认值。
     */
    public ConfigManager(Path dataDir) {
        this.configFile = dataDir.resolve("config.jsonc");
        Map<String, Object> parsed = null;
        if (Files.exists(configFile)) {
            try {
                String raw = Files.readString(configFile, StandardCharsets.UTF_8);
                parsed = parseJson(raw);
            } catch (Exception e) {
                // 解析失败 → 回退到默认值
                parsed = null;
            }
        }
        this.data = (parsed != null) ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
        resolveKeys();
    }

    // ==================== reading 配置 ====================

    /** 段首是否添加两个全角空格缩进，默认 true */
    public boolean isFirstLineIndent() {
        return getNestedBoolean("reading", "firstLineIndent", DEFAULT_FIRST_LINE_INDENT);
    }

    /** 页面底部保留行数，范围 [0, 10]，默认 4 */
    public int getBottomMargin() {
        int val = getNestedInt("reading", "bottomMargin", DEFAULT_BOTTOM_MARGIN);
        return Math.max(0, Math.min(val, 10));
    }

    // ==================== display 配置 ====================

    /** 光标样式："block" / "underline" / "bar"，默认 "block" */
    public String getCursorStyle() {
        String val = getNestedString("display", "cursorStyle", DEFAULT_CURSOR_STYLE);
        if (val == null || !VALID_CURSOR_STYLES.contains(val)) {
            return DEFAULT_CURSOR_STYLE;
        }
        return val;
    }

    /** 光标颜色，格式 #RRGGBB，默认 "#c0c0c0" */
    public String getCursorColor() {
        String val = getNestedString("display", "cursorColor", DEFAULT_CURSOR_COLOR);
        if (val == null || !val.matches("#[0-9a-fA-F]{6}")) {
            return DEFAULT_CURSOR_COLOR;
        }
        return val;
    }

    /** 进度条位置："top" / "bottom" / "hidden"，默认 "bottom"。
     *  向后兼容：新键缺失时检查旧的 showProgressBar 布尔键（false→hidden）。 */
    public String getProgressBarPosition() {
        String val = getNestedString("display", "progressBarPosition", null);
        if (val != null && VALID_PROGRESS_BAR_POSITIONS.contains(val)) {
            return val;
        }
        // 向后兼容旧配置：showProgressBar: false → hidden
        Map<String, Object> sec = getSection("display");
        if (sec != null && sec.containsKey("showProgressBar")) {
            Object old = sec.get("showProgressBar");
            if (old instanceof Boolean && !((Boolean) old)) {
                return "hidden";
            }
        }
        return DEFAULT_PROGRESS_BAR_POSITION;
    }

    /** 是否显示底部命令面板（上下边框 + 命令输入行），默认 true */
    public boolean isShowCommandPanel() {
        return getNestedBoolean("display", "showCommandPanel", DEFAULT_SHOW_COMMAND_PANEL);
    }

    // ==================== general 配置 ====================

    /** 启动时是否自动扫描 books 目录，默认 true */
    public boolean isAutoScanBooks() {
        return getNestedBoolean("general", "autoScanBooks", DEFAULT_AUTO_SCAN_BOOKS);
    }

    /** 自动扫描的目录列表，默认 ["books"] */
    @SuppressWarnings("unchecked")
    public List<String> getScanDirectories() {
        Map<String, Object> section = getSection("general");
        if (section == null) return DEFAULT_SCAN_DIRECTORIES;

        Object val = section.get("scanDirectories");
        if (val instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object item : (List<?>) val) {
                if (item instanceof String && !((String) item).isBlank()) {
                    list.add((String) item);
                }
            }
            if (!list.isEmpty()) return list;
        }
        return DEFAULT_SCAN_DIRECTORIES;
    }

    /** 删除书籍前是否需要确认，默认 true */
    public boolean isConfirmDelete() {
        return getNestedBoolean("general", "confirmDelete", DEFAULT_CONFIRM_DELETE);
    }

    /** 添加书籍前是否需要确认，默认 true */
    public boolean isConfirmAdd() {
        return getNestedBoolean("general", "confirmAdd", DEFAULT_CONFIRM_ADD);
    }

    // ==================== 按键绑定 ====================

    /** 阅读模式下翻到下一页的按键集合 */
    public Set<Integer> getReadingNextPageKeys() { return readingNextPageKeys; }
    /** 阅读模式下翻到上一页的按键集合 */
    public Set<Integer> getReadingPrevPageKeys() { return readingPrevPageKeys; }
    /** 阅读模式下退出返回书架的按键 */
    public int getReadingExitKey() { return readingExitKey; }
    /** 激活命令行的按键（阅读模式和书架模式各自独立） */
    public int getReadingCommandKey() { return readingCommandKey; }

    /** 书架模式下打开选中书籍的按键 */
    public int getLibraryOpenKey() { return libraryOpenKey; }
    /** 书架模式下向上选择的按键 */
    public int getLibrarySelectUpKey() { return librarySelectUpKey; }
    /** 书架模式下向下选择的按键 */
    public int getLibrarySelectDownKey() { return librarySelectDownKey; }
    /** 书架模式下退出程序的按键 */
    public int getLibraryQuitKey() { return libraryQuitKey; }
    /** 书架模式下快速添加书籍的按键 */
    public int getLibraryQuickAddKey() { return libraryQuickAddKey; }
    /** 书架模式下快速删除书籍的按键 */
    public int getLibraryQuickDeleteKey() { return libraryQuickDeleteKey; }
    /** 书架模式下激活命令行的按键 */
    public int getLibraryCommandKey() { return libraryCommandKey; }

    // ==================== setter ====================

    public void setFirstLineIndent(boolean value) {
        setNested("reading", "firstLineIndent", value);
    }

    public void setBottomMargin(int value) {
        setNested("reading", "bottomMargin", Math.max(0, Math.min(value, 10)));
    }

    public void setCursorStyle(String value) {
        if (value != null && VALID_CURSOR_STYLES.contains(value)) {
            setNested("display", "cursorStyle", value);
        }
    }

    public void setCursorColor(String value) {
        if (value != null && value.matches("#[0-9a-fA-F]{6}")) {
            setNested("display", "cursorColor", value);
        }
    }

    public void setProgressBarPosition(String value) {
        if (value != null && VALID_PROGRESS_BAR_POSITIONS.contains(value)) {
            setNested("display", "progressBarPosition", value);
            // 清理旧键，避免 config.jsonc 中同时存在新旧两个键
            Map<String, Object> sec = getSection("display");
            if (sec != null) sec.remove("showProgressBar");
        }
    }

    public void setShowCommandPanel(boolean value) {
        setNested("display", "showCommandPanel", value);
    }

    public void setAutoScanBooks(boolean value) {
        setNested("general", "autoScanBooks", value);
    }

    public void setScanDirectories(List<String> value) {
        if (value != null && !value.isEmpty()) {
            setNested("general", "scanDirectories", new ArrayList<>(value));
        }
    }

    public void setConfirmDelete(boolean value) {
        setNested("general", "confirmDelete", value);
    }

    public void setConfirmAdd(boolean value) {
        setNested("general", "confirmAdd", value);
    }

    // ==================== 持久化 ====================

    /**
     * 将当前配置写回 config.jsonc。
     * 注意：不会保留原有注释，写入的是纯 JSON。
     */
    public void save() {
        try {
            String json = toJsonString(data);
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 静默忽略
        }
    }

    // ==================== 通用 set（供 /set 命令使用） ====================

    /**
     * 通过键名设置配置值。成功返回 null，失败返回错误描述。
     */
    public String set(String key, String value) {
        if (key == null || value == null) return "用法: /set <属性名> <值>";
        try {
            switch (key) {
                case "firstLineIndent":
                    if (!isBool(value)) return "值应为 true 或 false";
                    setFirstLineIndent(Boolean.parseBoolean(value));
                    break;
                case "bottomMargin":
                    setBottomMargin(Integer.parseInt(value));
                    break;
                case "cursorStyle":
                    if (!VALID_CURSOR_STYLES.contains(value)) return "cursorStyle 只能为 block / underline / bar";
                    setCursorStyle(value);
                    break;
                case "cursorColor":
                    setCursorColor(resolveCursorColor(value));
                    break;
                case "progressBarPosition":
                    setProgressBarPosition(resolveProgressBarPosition(value));
                    break;
                case "showCommandPanel":
                    if (!isBool(value)) return "值应为 true 或 false";
                    setShowCommandPanel(Boolean.parseBoolean(value));
                    break;
                case "autoScanBooks":
                    if (!isBool(value)) return "值应为 true 或 false";
                    setAutoScanBooks(Boolean.parseBoolean(value));
                    break;
                case "confirmDelete":
                    if (!isBool(value)) return "值应为 true 或 false";
                    setConfirmDelete(Boolean.parseBoolean(value));
                    break;
                case "confirmAdd":
                    if (!isBool(value)) return "值应为 true 或 false";
                    setConfirmAdd(Boolean.parseBoolean(value));
                    break;
                default:
                    return "未知属性: " + key + "。输入 /settings 查看可设置的属性";
            }
            save();
            return null; // 成功
        } catch (NumberFormatException e) {
            return "无效数值: " + value;
        } catch (Exception e) {
            return "设置失败: " + e.getMessage();
        }
    }

    private static boolean isBool(String s) {
        return "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
    }

    // ==================== 设置页面元数据 ====================

    public enum SettingType { BOOL, INT, ENUM, COLOR, STRING }

    public static class SettingItem {
        public final String key;
        public final String label;
        public final SettingType type;
        public final String[] options; // ENUM 类型的可选值，其他类型为 null
        public final int min, max;     // INT 类型的范围，其他类型为 0,0

        public SettingItem(String key, String label, SettingType type) {
            this(key, label, type, null, 0, 0);
        }
        public SettingItem(String key, String label, SettingType type, String[] options) {
            this(key, label, type, options, 0, 0);
        }
        public SettingItem(String key, String label, SettingType type, int min, int max) {
            this(key, label, type, null, min, max);
        }
        private SettingItem(String key, String label, SettingType type, String[] options, int min, int max) {
            this.key = key; this.label = label; this.type = type;
            this.options = options; this.min = min; this.max = max;
        }
    }

    public static class SettingSection {
        public final String key;
        public final String label;
        public final List<SettingItem> items;

        public SettingSection(String key, String label) {
            this.key = key; this.label = label;
            this.items = new ArrayList<>();
        }
        public void add(SettingItem item) { items.add(item); }
    }

    /** 构建设置页面的结构化元数据 */
    public List<SettingSection> getSettingSections() {
        List<SettingSection> sections = new ArrayList<>();

        SettingSection reading = new SettingSection("reading", "阅读设置");
        reading.add(new SettingItem("firstLineIndent", "段首缩进", SettingType.BOOL));
        reading.add(new SettingItem("bottomMargin", "底部边距", SettingType.INT, 0, 10));
        sections.add(reading);

        SettingSection display = new SettingSection("display", "显示设置");
        display.add(new SettingItem("cursorStyle", "光标样式", SettingType.ENUM,
                new String[]{"block", "underline", "bar"}));
        display.add(new SettingItem("cursorColor", "光标颜色", SettingType.ENUM, CURSOR_COLOR_LABELS));
        display.add(new SettingItem("progressBarPosition", "进度条位置", SettingType.ENUM,
                PROGRESS_BAR_POSITION_LABELS));
        display.add(new SettingItem("showCommandPanel", "命令面板", SettingType.BOOL));
        sections.add(display);

        SettingSection general = new SettingSection("general", "通用设置");
        general.add(new SettingItem("autoScanBooks", "自动扫描", SettingType.BOOL));
        general.add(new SettingItem("confirmDelete", "删除确认", SettingType.BOOL));
        general.add(new SettingItem("confirmAdd", "添加确认", SettingType.BOOL));
        sections.add(general);

        return sections;
    }

    /** 获取单个属性的当前值（字符串形式，供设置页面显示） */
    public String getValueString(String key) {
        switch (key) {
            case "firstLineIndent":    return String.valueOf(isFirstLineIndent());
            case "bottomMargin":       return String.valueOf(getBottomMargin());
            case "cursorStyle":        return getCursorStyle();
            case "cursorColor":        return cursorColorToLabel(getCursorColor());
            case "progressBarPosition": return progressBarPositionToLabel(getProgressBarPosition());
            case "showCommandPanel":   return String.valueOf(isShowCommandPanel());
            case "autoScanBooks":      return String.valueOf(isAutoScanBooks());
            case "confirmDelete":      return String.valueOf(isConfirmDelete());
            case "confirmAdd":         return String.valueOf(isConfirmAdd());
            default:                   return "?";
        }
    }

    // ==================== 按键解析 ====================

    /** 从 Map 中读取按键配置，值可以是 String 或 List */
    @SuppressWarnings("unchecked")
    private Set<Integer> getKeySet(Map<String, Object> section, String key, Set<Integer> defaults) {
        Object val = section.get(key);
        if (val instanceof List) {
            Set<Integer> result = new HashSet<>();
            for (Object item : (List<?>) val) {
                if (item instanceof String) {
                    int resolved = resolveKeyName((String) item);
                    if (resolved != UNKNOWN) result.add(resolved);
                }
            }
            return result.isEmpty() ? defaults : result;
        }
        if (val instanceof String) {
            int resolved = resolveKeyName((String) val);
            if (resolved != UNKNOWN) return Collections.singleton(resolved);
        }
        return defaults;
    }

    /** 从 Map 中读取单个按键配置 */
    private int getSingleKey(Map<String, Object> section, String key, int defaultVal) {
        Object val = section.get(key);
        if (val instanceof String) {
            int resolved = resolveKeyName((String) val);
            if (resolved != UNKNOWN) return resolved;
        }
        return defaultVal;
    }

    /** 按键名 → 整数值映射 */
    static int resolveKeyName(String name) {
        if (name == null) return UNKNOWN;
        switch (name) {
            case "Enter":     return -11;
            case "Space":     return ' ';
            case "ArrowUp":   return -20;
            case "ArrowDown": return -21;
            case "ArrowRight":return -22;
            case "ArrowLeft": return -23;
            case "Escape":    return -10;
            case "Tab":       return 9;
            case "Home":      return -24;
            case "End":       return -25;
            case "Delete":    return -26;
            case "Backspace": return -12;
            default:
                if (name.length() == 1) return name.charAt(0);
                return UNKNOWN;
        }
    }

    private static final int UNKNOWN = -99;

    /** 从已解析的 data map 中提取按键配置并缓存 */
    private void resolveKeys() {
        Map<String, Object> keysSection = getSection("keys");
        if (keysSection == null) return; // 全部使用默认值

        @SuppressWarnings("unchecked")
        Map<String, Object> reading = getSubSection(keysSection, "reading");
        if (reading != null) {
            readingNextPageKeys = getKeySet(reading, "nextPage", DEFAULT_NEXT_PAGE);
            readingPrevPageKeys = getKeySet(reading, "prevPage", DEFAULT_PREV_PAGE);
            readingExitKey = getSingleKey(reading, "exitReading", DEFAULT_EXIT_READING);
            readingCommandKey = getSingleKey(reading, "activateCommand", DEFAULT_ACTIVATE_CMD);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> library = getSubSection(keysSection, "library");
        if (library != null) {
            libraryOpenKey = getSingleKey(library, "openBook", DEFAULT_LIBRARY_OPEN);
            librarySelectUpKey = getSingleKey(library, "selectUp", DEFAULT_LIBRARY_UP);
            librarySelectDownKey = getSingleKey(library, "selectDown", DEFAULT_LIBRARY_DOWN);
            libraryQuitKey = getSingleKey(library, "quit", DEFAULT_QUIT);
            libraryQuickAddKey = getSingleKey(library, "quickAdd", DEFAULT_QUICK_ADD);
            libraryQuickDeleteKey = getSingleKey(library, "quickDelete", DEFAULT_QUICK_DELETE);
            libraryCommandKey = getSingleKey(library, "activateCommand", DEFAULT_ACTIVATE_CMD);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getSubSection(Map<String, Object> parent, String key) {
        Object val = parent.get(key);
        if (val instanceof Map) return (Map<String, Object>) val;
        return null;
    }

    // ==================== 内部辅助方法 ====================

    /** 确保 section 对应的 Map 存在，不存在则创建 */
    @SuppressWarnings("unchecked")
    private void setNested(String section, String key, Object value) {
        Map<String, Object> sec = (Map<String, Object>) data.computeIfAbsent(section, k -> new LinkedHashMap<>());
        sec.put(key, value);
    }

    private Map<String, Object> getSection(String sectionName) {
        Object section = data.get(sectionName);
        if (section instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) section;
            return map;
        }
        return null;
    }

    private String getNestedString(String section, String key, String defaultValue) {
        Map<String, Object> sec = getSection(section);
        if (sec == null) return defaultValue;
        Object val = sec.get(key);
        if (val instanceof String) return (String) val;
        return defaultValue;
    }

    private boolean getNestedBoolean(String section, String key, boolean defaultValue) {
        Map<String, Object> sec = getSection(section);
        if (sec == null) return defaultValue;
        Object val = sec.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return defaultValue;
    }

    private int getNestedInt(String section, String key, int defaultValue) {
        Map<String, Object> sec = getSection(section);
        if (sec == null) return defaultValue;
        Object val = sec.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    // ==================== JSON 解析器 ====================

    /**
     * 预处理 JSON 文本：去除 BOM、// 行注释、尾逗号。
     */
    static String preprocess(String json) {
        // 去除 BOM (U+FEFF)
        if (!json.isEmpty() && json.codePointAt(0) == 0xFEFF) {
            json = json.substring(1);
        }

        // 去除 // 行注释（不处理字符串内部，但简单场景足够）
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                i++;
                continue;
            }
            if (c == '\\' && inString) {
                sb.append(c);
                escaped = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                sb.append(c);
                i++;
                continue;
            }
            if (!inString && c == '/' && i + 1 < json.length() && json.charAt(i + 1) == '/') {
                // 跳过到行尾
                i += 2;
                while (i < json.length() && json.charAt(i) != '\n') i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        String result = sb.toString();

        // 去除尾逗号：, 后面紧跟 } 或 ]（允许空白）
        result = result.replaceAll(",\\s*([}\\]])", "$1");

        return result;
    }

    /**
     * 顶层解析入口。
     */
    static Map<String, Object> parseJson(String json) {
        String clean = preprocess(json);
        ParseContext ctx = new ParseContext(clean);
        ctx.skipWhitespace();
        Object result = parseValue(ctx);
        if (result instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            return map;
        }
        return Collections.emptyMap();
    }

    // ---- 解析上下文 ----

    private static class ParseContext {
        final String json;
        int pos;

        ParseContext(String json) { this.json = json; this.pos = 0; }

        char peek() {
            return pos < json.length() ? json.charAt(pos) : '\0';
        }

        char advance() {
            return pos < json.length() ? json.charAt(pos++) : '\0';
        }

        void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
        }
    }

    // ---- 值分发 ----

    private static Object parseValue(ParseContext ctx) {
        ctx.skipWhitespace();
        char c = ctx.peek();
        switch (c) {
            case '{': return parseObject(ctx);
            case '[': return parseArray(ctx);
            case '"': return parseString(ctx);
            case 't': case 'f': return parseBoolean(ctx);
            case 'n': return parseNull(ctx);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber(ctx);
                throw new RuntimeException("JSON 解析错误: 意外字符 '" + c + "' 位置 " + ctx.pos);
        }
    }

    // ---- 对象 ----

    private static Map<String, Object> parseObject(ParseContext ctx) {
        Map<String, Object> map = new LinkedHashMap<>();
        ctx.advance(); // 跳过 '{'
        ctx.skipWhitespace();
        if (ctx.peek() == '}') { ctx.advance(); return map; }

        while (true) {
            ctx.skipWhitespace();
            if (ctx.peek() == '}') { ctx.advance(); break; }

            // key 必须是字符串
            String key = parseString(ctx);
            ctx.skipWhitespace();
            if (ctx.advance() != ':') {
                throw new RuntimeException("JSON 解析错误: 期望 ':' 位置 " + (ctx.pos - 1));
            }
            Object value = parseValue(ctx);
            map.put(key, value);

            ctx.skipWhitespace();
            if (ctx.peek() == '}') { ctx.advance(); break; }
            if (ctx.peek() == ',') { ctx.advance(); continue; }
            // 尾逗号已由 preprocess 处理，这里不应出现其他情况
            throw new RuntimeException("JSON 解析错误: 期望 ',' 或 '}' 位置 " + ctx.pos);
        }
        return map;
    }

    // ---- 数组 ----

    private static List<Object> parseArray(ParseContext ctx) {
        List<Object> list = new ArrayList<>();
        ctx.advance(); // 跳过 '['
        ctx.skipWhitespace();
        if (ctx.peek() == ']') { ctx.advance(); return list; }

        while (true) {
            ctx.skipWhitespace();
            if (ctx.peek() == ']') { ctx.advance(); break; }
            list.add(parseValue(ctx));
            ctx.skipWhitespace();
            if (ctx.peek() == ']') { ctx.advance(); break; }
            if (ctx.advance() != ',') {
                throw new RuntimeException("JSON 解析错误: 期望 ',' 或 ']' 位置 " + (ctx.pos - 1));
            }
        }
        return list;
    }

    // ---- 字符串 ----

    private static String parseString(ParseContext ctx) {
        StringBuilder sb = new StringBuilder();
        ctx.advance(); // 跳过开头的 "
        while (ctx.pos < ctx.json.length()) {
            char c = ctx.advance();
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char esc = ctx.advance();
                switch (esc) {
                    case '"':  sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        // Unicode 转义 \\uXXXX
                        int codePoint = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = ctx.advance();
                            codePoint <<= 4;
                            if (hex >= '0' && hex <= '9') codePoint |= (hex - '0');
                            else if (hex >= 'a' && hex <= 'f') codePoint |= (hex - 'a' + 10);
                            else if (hex >= 'A' && hex <= 'F') codePoint |= (hex - 'A' + 10);
                        }
                        sb.append((char) codePoint);
                        break;
                    default:
                        // 未知转义，原样保留
                        sb.append('\\').append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- 布尔 ----

    private static Boolean parseBoolean(ParseContext ctx) {
        if (ctx.json.startsWith("true", ctx.pos)) {
            ctx.pos += 4;
            return Boolean.TRUE;
        }
        if (ctx.json.startsWith("false", ctx.pos)) {
            ctx.pos += 5;
            return Boolean.FALSE;
        }
        throw new RuntimeException("JSON 解析错误: 期望 true/false 位置 " + ctx.pos);
    }

    // ---- null ----

    private static Object parseNull(ParseContext ctx) {
        if (ctx.json.startsWith("null", ctx.pos)) {
            ctx.pos += 4;
            return null;
        }
        throw new RuntimeException("JSON 解析错误: 期望 null 位置 " + ctx.pos);
    }

    // ---- 数字 ----

    private static Number parseNumber(ParseContext ctx) {
        int start = ctx.pos;
        // 负号
        if (ctx.peek() == '-') ctx.advance();
        // 整数部分
        while (ctx.pos < ctx.json.length() && Character.isDigit(ctx.peek())) ctx.advance();
        boolean isDouble = false;
        // 小数部分
        if (ctx.peek() == '.') {
            isDouble = true;
            ctx.advance();
            while (ctx.pos < ctx.json.length() && Character.isDigit(ctx.peek())) ctx.advance();
        }
        // 指数部分
        char c = ctx.peek();
        if (c == 'e' || c == 'E') {
            isDouble = true;
            ctx.advance();
            if (ctx.peek() == '+' || ctx.peek() == '-') ctx.advance();
            while (ctx.pos < ctx.json.length() && Character.isDigit(ctx.peek())) ctx.advance();
        }
        String numStr = ctx.json.substring(start, ctx.pos);
        if (isDouble) return Double.parseDouble(numStr);
        // 尝试整数解析
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return Long.parseLong(numStr);
        }
    }

    // ---- 序列化 ----

    /** 将内存中的配置树序列化为格式化的 JSON 字符串 */
    @SuppressWarnings("unchecked")
    static String toJsonString(Object value) {
        return toJson(value, 0);
    }

    private static String toJson(Object value, int indent) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return value.toString();
        if (value instanceof String) return jsonEscape((String) value);
        if (value instanceof Map) return toJsonObject((Map<String, Object>) value, indent);
        if (value instanceof List) return toJsonArray((List<?>) value, indent);
        return "\"\"";
    }

    private static String toJsonObject(Map<String, Object> map, int indent) {
        if (map.isEmpty()) return "{}";
        String pad = "  ".repeat(indent);
        String innerPad = "  ".repeat(indent + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sb.append(innerPad).append(jsonEscape(entry.getKey())).append(": ");
            sb.append(toJson(entry.getValue(), indent + 1));
            if (++i < map.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append(pad).append("}");
        return sb.toString();
    }

    private static String toJsonArray(List<?> list, int indent) {
        if (list.isEmpty()) return "[]";
        // 简单字符串列表：单行紧凑输出
        boolean allStrings = list.stream().allMatch(o -> o instanceof String);
        if (allStrings && list.size() <= 5) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(jsonEscape((String) list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        String pad = "  ".repeat(indent);
        String innerPad = "  ".repeat(indent + 1);
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(innerPad).append(toJson(list.get(i), indent + 1));
            if (i < list.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(pad).append("]");
        return sb.toString();
    }

    /** JSON 字符串转义 */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
