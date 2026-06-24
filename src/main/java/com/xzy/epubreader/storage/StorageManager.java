package com.xzy.epubreader.storage;

import com.xzy.epubreader.model.LibraryEntry;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储管理器：书架 + 阅读进度持久化。
 *
 * 数据目录优先级：
 *   1. 如果 JAR 位于某个 dist/ 目录内 → dist/.epub-reader/
 *   2. 如果当前工作目录下存在 dist/ 目录 → dist/.epub-reader/
 *   3. 否则 → ~/.epub-reader/ (默认)
 *
 * 数据目录结构：
 *   library.json        书架列表
 *   progress/            每本书一个进度文件
 */
public class StorageManager {

    private final Path dataDir;
    private final Path libraryFile;
    private final Path progressDir;

    public StorageManager() {
        this.dataDir = resolveDataDir();
        this.libraryFile = dataDir.resolve("library.json");
        this.progressDir = dataDir.resolve("progress");
    }

    /**
     * 按优先级确定数据存储目录。
     * 优先使用 dist/ 目录（如果 JAR 运行在 dist 环境），否则回退到用户目录。
     */
    private static Path resolveDataDir() {
        // 1. 尝试从 JAR 位置推断
        Path jarDir = getJarDirectory();
        if (jarDir != null) {
            // JAR 自身就在 dist/ 下，例如 dist/epub-reader.jar
            if (jarDir.getFileName() != null && "dist".equals(jarDir.getFileName().toString())) {
                Path dataDir = jarDir.resolve(".epub-reader");
                if (Files.isDirectory(dataDir) || jarDir.toFile().canWrite()) {
                    return dataDir;
                }
            }
            // JAR 所在目录存在 dist/ 子目录
            Path siblingDist = jarDir.resolve("dist");
            if (Files.isDirectory(siblingDist)) {
                Path dataDir = siblingDist.resolve(".epub-reader");
                return dataDir;
            }
        }

        // 2. 尝试当前工作目录下的 dist/
        Path cwdDist = Paths.get("dist");
        if (Files.isDirectory(cwdDist)) {
            return cwdDist.resolve(".epub-reader").toAbsolutePath();
        }

        // 3. 回退到用户目录
        String userHome = System.getProperty("user.home", ".");
        return Paths.get(userHome, ".epub-reader");
    }

    /**
     * 获取 JAR 文件所在的目录，如果无法确定（例如从 IDE 直接运行）则返回 null。
     */
    private static Path getJarDirectory() {
        try {
            CodeSource codeSource = StorageManager.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) return null;
            URL location = codeSource.getLocation();
            if (location == null) return null;
            Path jarPath = Paths.get(location.toURI());
            // 确保是文件（JAR），不是 target/classes 这种目录
            if (Files.isRegularFile(jarPath)) {
                Path parent = jarPath.getParent();
                if (parent != null) return parent.toAbsolutePath();
            }
        } catch (URISyntaxException e) {
            // 无法确定，忽略
        }
        return null;
    }

    /** 返回数据目录路径，供 ConfigManager 等使用 */
    public Path getDataDir() {
        return dataDir;
    }

    public void init() throws IOException {
        Files.createDirectories(dataDir);
        Files.createDirectories(progressDir);
    }

    // ==================== 书架管理 ====================

    /**
     * 加载书架列表。
     */
    public List<LibraryEntry> loadLibrary() {
        List<LibraryEntry> entries = new ArrayList<>();
        if (!Files.exists(libraryFile)) {
            return entries;
        }

        try {
            String json = Files.readString(libraryFile, StandardCharsets.UTF_8);
            json = json.trim();
            if (json.equals("{}") || json.isBlank()) {
                return entries;
            }

            // 解析: {"books": [{...}, {...}]}
            int booksStart = json.indexOf("\"books\"");
            if (booksStart < 0) return entries;
            int arrStart = json.indexOf('[', booksStart);
            int arrEnd = json.lastIndexOf(']');
            if (arrStart < 0 || arrEnd < 0 || arrEnd <= arrStart) return entries;

            String arr = json.substring(arrStart + 1, arrEnd).trim();
            if (arr.isBlank()) return entries;

            // 分割每个对象 { ... }
            List<String> objects = splitJsonObjects(arr);
            for (String obj : objects) {
                String path = extractString(obj, "path");
                String title = extractString(obj, "title");
                String author = extractString(obj, "author");
                if (path != null && !path.isBlank()) {
                    entries.add(new LibraryEntry(path, title, author));
                }
            }
        } catch (IOException e) {
            // 文件损坏，返回空列表
        }

        return entries;
    }

    /**
     * 保存书架列表。
     */
    public void saveLibrary(List<LibraryEntry> entries) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"books\": [\n");

            for (int i = 0; i < entries.size(); i++) {
                LibraryEntry e = entries.get(i);
                sb.append("    {\n");
                sb.append("      \"path\": ").append(jsonString(e.getPath()));
                sb.append(",\n      \"title\": ").append(jsonString(e.getTitle()));
                sb.append(",\n      \"author\": ").append(jsonString(e.getAuthor()));
                sb.append(",\n      \"lastReadAt\": ").append(jsonString(LocalDateTime.now()
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
                sb.append("\n    }");
                if (i < entries.size() - 1) sb.append(",");
                sb.append("\n");
            }

            sb.append("  ]\n}\n");

            Files.writeString(libraryFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // 保存失败静默处理
        }
    }

    // ==================== 阅读进度（每本书单独文件） ====================

    /** 把书路径映射成一个安全的文件名 */
    private Path progressPath(String bookPath) {
        String safe = bookPath.replace('\\', '/')
                .replace(':', '_')
                .replace('/', '_');
        return progressDir.resolve(safe + ".txt");
    }

    /**
     * 加载阅读进度。null 表示没有进度。
     */
    public int[] loadProgress(String bookPath) {
        Path file = progressPath(bookPath);
        if (!Files.exists(file)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            String[] parts = content.split(",");
            if (parts.length == 2) {
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            }
        } catch (Exception e) {
            // 损坏则忽略
        }
        return null;
    }

    /**
     * 保存阅读进度（只写入不到 50 字节的小文件，不会 OOM）。
     */
    public void saveProgress(String bookPath, int chapter, int page) {
        try {
            String content = chapter + "," + page;
            Files.writeString(progressPath(bookPath), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // 静默忽略
        }
    }

    // ==================== JSON 工具方法 ====================

    /**
     * 用双引号包裹字符串并转义特殊字符。
     */
    /** 把字符串编码为 JSON 字符串值（只转义 \ 和 "，不转义控制字符以避免与 Windows 路径冲突） */
    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 生成用于匹配 JSON key 的转义字符串（不含外层引号）。
     */
    private static String jsonStringKey(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else sb.append(c);
        }
        return "\"" + sb.toString() + "\"";
    }

    /**
     * 从 JSON 字符串中提取某个字段的字符串值。
     */
    private static String extractString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        // 找 key 后面的第一个冒号后的第一个引号
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;

        // 找结束引号（处理转义）
        int endQuote = startQuote + 1;
        while (endQuote < json.length()) {
            if (json.charAt(endQuote) == '"' && json.charAt(endQuote - 1) != '\\') {
                break;
            }
            endQuote++;
        }

        if (endQuote >= json.length()) return null;
        String value = json.substring(startQuote + 1, endQuote);
        // 反转义：先还原 \"，再还原 \\
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * 从 JSON 对象中提取整数值。
     */
    private static int extractInt(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return -1;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return -1;

        // 跳过空白和冒号
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return -1;

        // 读取数字
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;

        if (end == start) return -1;
        return Integer.parseInt(json.substring(start, end));
    }

    /**
     * 将 JSON 数组中的顶层对象分开。
     * 例如: "{...}, {...}" -> ["{...}", "{...}"]
     */
    private static List<String> splitJsonObjects(String s) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    result.add(s.substring(start, i + 1));
                }
            }
        }

        return result;
    }
}
