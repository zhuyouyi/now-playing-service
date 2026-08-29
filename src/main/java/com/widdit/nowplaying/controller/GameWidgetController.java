package com.widdit.nowplaying.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 游戏组件的页面路由：
 * - /widget/game   OBS 浏览器源加载的组件页
 * - /game          客户端管理页（美化配置 + 进程映射 + 预览）
 */
@Controller
public class GameWidgetController {

    @GetMapping("/widget/game")
    public String widget() {
        return "game-widget";
    }

    @GetMapping("/game")
    public String client() {
        return "game-client";
    }

}
