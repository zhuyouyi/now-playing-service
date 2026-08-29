package com.widdit.nowplaying.controller;

import com.widdit.nowplaying.entity.Game;
import com.widdit.nowplaying.entity.GameSettings;
import com.widdit.nowplaying.service.GameService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 游戏组件 API：当前游戏、美化设置、运行中的进程列表。
 */
@RestController
@Slf4j
public class GameController {

    @Autowired
    private GameService gameService;

    /**
     * 获取当前正在玩的游戏（多平台）。未在游玩时 running=false。
     */
    @GetMapping({"/api/query/game", "/query/game"})
    public Game game() {
        return gameService.query();
    }

    /**
     * 获取游戏组件美化设置。
     */
    @GetMapping("/api/game/settings")
    public GameSettings settings() {
        return gameService.getSettings();
    }

    /**
     * 更新游戏组件美化设置。
     */
    @PutMapping("/api/game/settings")
    public GameSettings updateSettings(@RequestBody GameSettings settings) {
        gameService.updateSettings(settings);
        return gameService.getSettings();
    }

    /**
     * 当前运行的进程（用于客户端配置进程映射时的提示）。
     */
    @GetMapping("/api/game/processes")
    public List<String> processes() {
        return gameService.runningProcesses();
    }

}
