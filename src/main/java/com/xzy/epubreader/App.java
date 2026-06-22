package com.xzy.epubreader;

import com.xzy.epubreader.ui.TerminalUI;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * EPUB 终端阅读器入口。
 *
 * 用法:
 *   java -jar epub-reader.jar               → 启动书架
 *   java -jar epub-reader.jar <epub文件>    → 直接打开文件
 */
public class App {

    public static void main(String[] args) {
        // 设置 Windows 控制台为 UTF-8
        setConsoleToUtf8();
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        try {
            TerminalUI ui = new TerminalUI();

            if (args.length >= 1) {
                // 直接打开文件
                String filePath = args[0];
                File file = new File(filePath);
                if (!file.exists()) {
                    System.err.println("文件不存在: " + filePath);
                    System.exit(1);
                }
                ui.openDirectly(filePath);
            } else {
                // 启动书架
                ui.start();
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void setConsoleToUtf8() {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("win")) {
                new ProcessBuilder("cmd", "/c", "chcp", "65001", ">nul")
                        .inheritIO().start().waitFor();
            }
        } catch (Exception e) {
            // 静默忽略
        }
    }
}
