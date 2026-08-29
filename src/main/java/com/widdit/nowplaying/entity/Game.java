package com.widdit.nowplaying.entity;

import lombok.Data;

/**
 * 当前正在玩的游戏（多平台）。与音乐的 Track 实体并列，字段更贴合游戏场景。
 */
@Data
public class Game {

    // 是否正在游玩
    private boolean running;

    // 平台：steam / epic / gog / ubisoft / custom
    private String platform;

    // 平台显示名：Steam / Epic / GOG / Ubisoft / 其它平台
    private String platformLabel;

    // 游戏名
    private String name;

    // Steam AppID（非 Steam 平台为 0 / null）
    private Integer appid;

    // 封面图 URL（Steam header；其它平台默认无）
    private String cover;

    // 本次会话时长（秒）
    private Long sessionSeconds;

    // 累计游玩时长（分钟，来自 appmanifest）
    private Integer playtimeMinutes;

    // 数据来源：registry / process / non-windows / none
    private String source;

}
