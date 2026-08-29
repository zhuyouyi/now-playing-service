package com.widdit.nowplaying.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/widget")
    public String widget() {
        return "widget";
    }

    @GetMapping("/widget/*")
    public String widgetWithProfile() {
        return "widget";
    }

    @GetMapping("/widget-widdit")
    public String widgetWiddit() {
        return "widget-widdit";
    }

    @GetMapping("/widget-widdit/*")
    public String widgetWidditWithProfile() {
        return "widget-widdit";
    }

    @GetMapping("/settings/widget")
    public String settingsWidget() {
        // 旧版 settings-widget.html 引用不存在的 /assets 资源，改为返回 React 前端壳（由前端路由渲染歌曲组件页）
        return "index";
    }

    /**
     * 捕获所有其它路由
     * 排除 /api、/assets、/vite-assets、/public
     */
    @GetMapping({
            "/",
            "/{path:^(?!api|assets|vite-assets|public).*}/**"
    })
    public String index() {
        return "index";
    }

}
