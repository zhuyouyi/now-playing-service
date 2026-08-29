package com.widdit.nowplaying.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.widdit.nowplaying.entity.Game;
import com.widdit.nowplaying.entity.GameProcess;
import com.widdit.nowplaying.entity.GameSettings;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多平台游戏检测服务（与音乐检测并行，互不影响）。
 * - Steam：每 1.5 秒读注册表 HKCU\Software\Valve\Steam\RunningAppID，最可靠。
 * - 其它平台（Epic/GOG/Ubisoft/其它）：进程检测 tasklist，按 GameSettings.customGames 映射识别。
 */
@Service
@Slf4j
public class GameService {

    private static final String STORE_URL =
            "https://store.steampowered.com/api/appdetails?appids=%d&l=schinese&cc=cn";
    private static final String HEADER_CDN =
            "https://cdn.cloudflare.steamstatic.com/steam/apps/%d/header.jpg";
    private static final String REG_KEY = "HKCU\\Software\\Valve\\Steam";
    private static final long STORE_COOLDOWN_MS = 60_000L;
    private static final Charset WIN_CHARSET = Charset.forName("GBK");

    private final boolean isWindows =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private volatile Game game = new Game();
    private GameSettings settings = loadGameSettings();

    private final Map<Integer, String> nameCache = new HashMap<>();
    private final Map<Integer, Long> storeCooldown = new HashMap<>();
    private String prevKey = "";
    private long startedAt = 0L;

    @Scheduled(fixedDelay = 1500)
    public void sample() {
        if (!isWindows) {
            setGame(new Game(), "non-windows");
            return;
        }

        long now = System.currentTimeMillis();

        // 1) Steam 注册表（最可靠）
        int appid = readSteamAppId();
        if (appid != 0) {
            String key = "steam:" + appid;
            setGame(buildGame(true, "steam", "Steam",
                    resolveSteamName(appid), appid,
                    String.format(HEADER_CDN, appid),
                    sessionSeconds(key, now), null, "registry"),
                    key);
            return;
        }

        // 2) 其它平台：进程检测
        GameProcess hit = detectProcess();
        if (hit != null) {
            String platform = hit.getPlatform() == null ? "custom" : hit.getPlatform();
            String key = "proc:" + platform.toLowerCase() + ":" + hit.getName();
            String label = platformLabel(platform);
            setGame(buildGame(true, platform, label, hit.getName(), null, null,
                    sessionSeconds(key, now), null, "process"), key);
            return;
        }

        prevKey = "";
        startedAt = 0L;
        setGame(new Game(), "none");
    }

    // ---------- 对外 ----------

    public Game getGame() {
        return game;
    }

    public Game query() {
        return game;
    }

    public GameSettings getSettings() {
        return settings;
    }

    public void updateSettings(GameSettings s) {
        if (s.getCustomGames() == null) {
            s.setCustomGames(new HashMap<>());
        }
        this.settings = s;
        saveGameSettings(s);
    }

    public List<String> runningProcesses() {
        return detectRunningProcesses();
    }

    // ---------- 构建 ----------

    private Game buildGame(boolean running, String platform, String label, String name,
                           Integer appid, String cover, long session, Integer playtime, String source) {
        Game g = new Game();
        g.setRunning(running);
        g.setPlatform(platform);
        g.setPlatformLabel(label);
        g.setName(name);
        g.setAppid(appid);
        g.setCover(cover);
        g.setSessionSeconds(session);
        g.setPlaytimeMinutes(playtime);
        g.setSource(source);
        return g;
    }

    private void setGame(Game g, String key) {
        if (!"none".equals(key) && !"non-windows".equals(key) && !key.isBlank()) {
            // session 计时已在 sessionSeconds 内处理
        }
        this.game = g;
    }

    private long sessionSeconds(String key, long now) {
        if (!key.equals(prevKey)) {
            prevKey = key;
            startedAt = now;
        }
        return startedAt > 0 ? (now - startedAt) / 1000L : 0L;
    }

    private String platformLabel(String platform) {
        switch (platform) {
            case "steam": return "Steam";
            case "epic": return "Epic";
            case "gog": return "GOG";
            case "ubisoft": return "Ubisoft";
            default: return "其它平台";
        }
    }

    // ---------- Steam ----------

    private int readSteamAppId() {
        String out = runCommand("reg", "query", REG_KEY, "/v", "RunningAppID");
        Matcher m = Pattern.compile("RunningAppID\\s+REG_SZ\\s+(\\d+)").matcher(out);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private String steamPath() {
        String out = runCommand("reg", "query", REG_KEY, "/v", "SteamPath");
        Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
        if (m.find()) {
            return m.group(1).trim().replace("/", "\\");
        }
        return "";
    }

    private List<Path> steamRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(Paths.get("C:\\Program Files (x86)\\Steam"));
        roots.add(Paths.get("C:\\Program Files\\Steam"));
        String sp = steamPath();
        if (!sp.isEmpty()) {
            roots.add(Paths.get(sp));
        }
        Set<Path> seen = new LinkedHashSet<>(roots);
        List<Path> base = new ArrayList<>(roots);
        for (Path root : base) {
            Path lf = root.resolve("steamapps").resolve("libraryfolders.vdf");
            if (!Files.exists(lf)) {
                continue;
            }
            try {
                String txt = new String(Files.readAllBytes(lf), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"path\"\\s*\"([^\"]+)\"").matcher(txt);
                while (m.find()) {
                    Path lib = Paths.get(m.group(1).replace("\\\\", "\\"));
                    if (Files.exists(lib)) {
                        seen.add(lib);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return new ArrayList<>(seen);
    }

    private Path findManifest(int appid) {
        for (Path root : steamRoots()) {
            Path f = root.resolve("steamapps").resolve("appmanifest_" + appid + ".acf");
            if (Files.exists(f)) {
                return f;
            }
        }
        return null;
    }

    private String readAcf(Path manifest, String key) {
        if (manifest == null || !Files.exists(manifest)) {
            return null;
        }
        try {
            String txt = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
            for (String line : txt.split("\r?\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.equals("{") || t.equals("}")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (key.equals(t.substring(0, eq).trim().replace("\"", ""))) {
                    return t.substring(eq + 1).trim().replace("\"", "");
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private Integer readPlaytime(int appid) {
        String v = readAcf(findManifest(appid), "Playtime");
        if (v != null && v.matches("\\d+")) {
            return Integer.parseInt(v);
        }
        return null;
    }

    private String resolveSteamName(int appid) {
        String local = readAcf(findManifest(appid), "name");
        if (nameCache.containsKey(appid)) {
            return nameCache.get(appid);
        }
        long now = System.currentTimeMillis();
        Long last = storeCooldown.get(appid);
        if (last != null && now - last < STORE_COOLDOWN_MS) {
            return local != null ? local : ("AppID " + appid);
        }
        String store = fetchStoreName(appid);
        if (store != null) {
            nameCache.put(appid, store);
            return store;
        }
        storeCooldown.put(appid, now);
        return local != null ? local : ("AppID " + appid);
    }

    private String fetchStoreName(int appid) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(String.format(STORE_URL, appid));
            get.setHeader("User-Agent", "Mozilla/5.0");
            try (CloseableHttpResponse resp = client.execute(get)) {
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                JSONObject root = JSON.parseObject(body);
                JSONObject entry = root.getJSONObject(String.valueOf(appid));
                if (entry != null && entry.getBooleanValue("success")) {
                    JSONObject data = entry.getJSONObject("data");
                    if (data != null) {
                        return data.getString("name");
                    }
                }
            }
        } catch (Exception e) {
            // 网络失败/超时，走本地兜底
        }
        return null;
    }

    // ---------- 进程检测 ----------

    private List<String> detectRunningProcesses() {
        String out = runCommand("tasklist", "/FO", "CSV", "/NH");
        Set<String> names = new HashSet<>();
        for (String line : out.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            line = line.startsWith("\"") ? line.substring(1) : line;
            String name = line.split("\"", 2)[0].trim().toLowerCase();
            if (name.endsWith(".exe")) {
                name = name.substring(0, name.length() - 4);
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        List<String> list = new ArrayList<>(names);
        Collections.sort(list);
        return list;
    }

    private GameProcess detectProcess() {
        Set<String> procs = new HashSet<>(detectRunningProcesses());
        for (Map.Entry<String, GameProcess> e : settings.getCustomGames().entrySet()) {
            String key = e.getKey().toLowerCase().replaceAll("\\.exe$", "");
            if (procs.contains(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ---------- 命令 ----------

    private String runCommand(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), WIN_CHARSET))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 设置读写 ----------

    private static GameSettings loadGameSettings() {
        GameSettings s = defaultSettings();
        String filePath = "Settings\\game-settings.json";
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            writeJson(path, s);
            return s;
        }
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            GameSettings loaded = JSON.parseObject(content, GameSettings.class);
            if (loaded != null) {
                if (loaded.getCustomGames() == null) {
                    loaded.setCustomGames(new HashMap<>());
                }
                return loaded;
            }
        } catch (Exception e) {
            log.warn("加载 game-settings.json 失败：" + e.getMessage());
        }
        return s;
    }

    private static GameSettings defaultSettings() {
        GameSettings s = new GameSettings();
        Map<String, GameProcess> defaults = new HashMap<>();
        defaults.put("eldenring", new GameProcess("艾尔登法环", "epic"));
        defaults.put("cyberpunk2077", new GameProcess("赛博朋克 2077", "epic"));
        defaults.put("witcher3", new GameProcess("巫师 3", "gog"));
        defaults.put("acvalhalla", new GameProcess("刺客信条：英灵殿", "ubisoft"));
        s.setCustomGames(defaults);
        return s;
    }

    private static void saveGameSettings(GameSettings s) {
        Path dir = Paths.get("Settings");
        if (!Files.exists(dir)) {
            try {
                Files.createDirectory(dir);
            } catch (Exception e) {
                // ignore
            }
        }
        writeJson(Paths.get("Settings\\game-settings.json"), s);
    }

    private static void writeJson(Path path, Object obj) {
        try {
            Files.write(path, JSON.toJSONString(obj, true).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("写入游戏设置失败：" + e.getMessage());
        }
    }
}
