package com.widdit.nowplaying.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 进程映射：一个游戏进程对应一个显示名与平台。
 * 用于 Steam 之外（Epic/GOG/Ubisoft/其它）的进程检测。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GameProcess {

    // 显示的游戏名
    private String name;

    // 平台标识：epic / gog / ubisoft / custom
    private String platform;

}
