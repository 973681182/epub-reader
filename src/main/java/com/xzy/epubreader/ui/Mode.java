package com.xzy.epubreader.ui;

/**
 * 应用运行模式。
 */
public enum Mode {
    /** 书架模式：浏览、添加、删除书籍 */
    LIBRARY,
    /** 命令模式：输入斜杠命令操作当前书籍 */
    COMMAND,
    /** 阅读模式：翻页阅读书籍内容 */
    READING,
    /** 设置模式：浏览和修改配置 */
    SETTINGS
}
