package com.widdit.nowplaying.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 游戏组件的自定义美化设置（保存在 Settings/game-settings.json）。
 */
@Data
public class GameSettings {

    // 卡片位置：tl / tr / bl / br
    private String pos = "bl";

    // 主题：dark / light
    private String theme = "dark";

    // 强调色（HEX）
    private String accent = "#66c0f4";

    // 游戏名字号（px）
    private Integer fontSize = 20;

    // 卡片高度（px）
    private Integer height = 76;

    // 是否显示封面
    private Boolean showCover = true;

    // 是否显示 AppID
    private Boolean showAppid = true;

    // 是否显示平台标识
    private Boolean showPlatform = true;

    // 是否显示本次时长
    private Boolean showTime = true;

    // 进程映射：进程名（可省略 .exe，小写） -> {name, platform}
    private Map<String, GameProcess> customGames = new HashMap<>();

}
