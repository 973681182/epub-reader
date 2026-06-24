package com.xzy.epubreader.ui;

/**
 * 命令执行结果枚举。
 */
public enum CommandResult {
    /** 无操作（非命令输入或无匹配） */
    NONE,
    /** 进入阅读模式 */
    ENTER_READING,
    /** 显示章节目录 */
    SHOW_TOC,
    /** 显示详细进度 */
    SHOW_PROGRESS,
    /** 显示书籍信息 */
    SHOW_INFO,
    /** 显示设置页面 */
    SHOW_SETTINGS,
    /** 显示帮助 */
    SHOW_HELP,
    /** 返回书架 */
    BACK_TO_LIBRARY,
    /** 退出程序 */
    QUIT
}
