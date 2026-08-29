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

        try {
            long now = System.currentTimeMillis();

            // 1) 枚举系统里所有正在运行的进程（Oopz/Kook/Discord 同款机制，不依赖前台窗口）
            List<RunningProc> procs = enumerateProcesses();
            if (procs.isEmpty()) {
                prevKey = "";
                startedAt = 0L;
                setGame(new Game(), "none");
                return;
            }

            // 2) Steam 注册表快速识别（最可靠）
            int appid = readSteamAppId();
            if (appid != 0) {
                String key = "steam:" + appid;
                log.info("检测到 Steam 游戏 appid={}", appid);
                setGame(buildGame(true, "steam", "Steam",
                        resolveSteamName(appid), appid,
                        String.format(HEADER_CDN, appid),
                        sessionSeconds(key, now), readPlaytime(appid), "registry"), key);
                return;
            }

            // 3) 用户自定义进程映射（精确匹配进程名）
            GameProcess custom = detectCustomGame(procs);
            if (custom != null) {
                String platform = custom.getPlatform() == null ? "custom" : custom.getPlatform();
                String key = "proc:" + platform.toLowerCase() + ":" + custom.getName();
                String label = platformLabel(platform);
                log.info("检测到进程游戏 {} ({})", custom.getName(), label);
                setGame(withProc(buildGame(true, platform, label, custom.getName(), null, null,
                        sessionSeconds(key, now), null, "custom"), custom.getName()), key);
                return;
            }

            // 4) 自动进程映射：从剩余进程里挑出“看起来像在玩的游戏”的进程
            RunningProc auto = autoDetectGame(procs);
            if (auto != null) {
                String name = auto.displayName;
                String platform = guessPlatform(auto.exe);
                String key = "auto:" + auto.exe;
                String label = platformLabel(platform);
                log.info("自动识别到游戏进程 {} -> {} ({})", auto.exe, name, label);
                setGame(withProc(buildGame(true, platform, label, name, null, null,
                        sessionSeconds(key, now), null, "auto"), auto.exe), key);
                return;
            }

            prevKey = "";
            startedAt = 0L;
            setGame(new Game(), "none");
        } catch (Exception e) {
            log.warn("游戏检测出错: {}", e.getMessage());
        }
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

    private Game withProc(Game g, String procName) {
        g.setProcessName(procName);
        g.setDetectedName(g.getName());
        return g;
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
            case "mihoyo": return "米哈游";
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

    // ---------- 进程检测（监听系统全部进程，不依赖前台窗口）----------

    /** 运行中的进程：exe 为小写进程名（不含 .exe），displayName 为自动推断的显示名。 */
    static class RunningProc {
        final String exe;
        final String displayName;

        RunningProc(String exe, String displayName) {
            this.exe = exe;
            this.displayName = displayName;
        }
    }

    /**
     * 枚举系统所有正在运行的进程的可执行文件名。
     * 使用 Windows 自带的 tasklist（可靠、无需 JNA，避免不同 JNA 版本的 HANDLE 签名差异）。
     */
    private List<RunningProc> enumerateProcesses() {
        List<RunningProc> result = new ArrayList<>();
        String out = runCommand("tasklist", "/FO", "CSV", "/NH");
        if (out == null || out.isEmpty()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : out.split("\r?\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            line = line.startsWith("\"") ? line.substring(1) : line;
            String exe = line.split("\"", 2)[0].trim();
            String name = exe.toLowerCase().replaceAll("\\.exe$", "");
            if (!name.isEmpty() && seen.add(name)) {
                result.add(new RunningProc(name, prettify(name)));
            }
        }
        return result;
    }

    /** 用户自定义进程映射：进程名精确匹配。 */
    private GameProcess detectCustomGame(List<RunningProc> procs) {
        if (settings.getCustomGames() == null) {
            return null;
        }
        for (RunningProc p : procs) {
            GameProcess gp = settings.getCustomGames().get(p.exe);
            if (gp != null) {
                return gp;
            }
        }
        return null;
    }

    /** 自动识别：从剩余进程里挑一个“最像在玩”的游戏进程。 */
    private RunningProc autoDetectGame(List<RunningProc> procs) {
        // 不再依赖任何内置游戏库：只要不是系统进程、也不是常见后台工具的可执行进程，就认为是候选游戏。
        for (RunningProc p : procs) {
            if (!isSystemProcess(p.exe) && !isBackgroundTool(p.exe)) {
                return p;
            }
        }
        return null;
    }

    private boolean isSystemProcess(String name) {
        return SYSTEM_PROCESSES.contains(name);
    }

    private boolean isBackgroundTool(String name) {
        return BACKGROUND_TOOLS.contains(name);
    }

    /** 从进程名生成一个更可读的显示名（去掉常见后缀，替换分隔符）。 */
    private static String prettify(String raw) {
        String s = raw;
        s = s.replaceAll("(?i)(-?win64-shipping|-win64|-win32|-x64|-client|-game|_x64|-shipping|-launcher|-webhelper|-helper|-service)$", "");
        s = s.replaceAll("[_\\-]+", " ");
        s = s.trim();
        if (s.isEmpty()) {
            return raw;
        }
        // 首字母大写的英文/拼音词
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (w.length() > 0) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /** 根据进程可执行名粗略判断平台。 */
    private String guessPlatform(String exe) {
        if (exe.contains("valorant") || exe.contains("league") || exe.contains("riot")
                || exe.contains("r5apex") || exe.contains("fortnite")) {
            return "epic";
        }
        if (exe.contains("ac") || exe.contains("farcry") || exe.contains("division")
                || exe.contains("rainbowsix")) {
            return "ubisoft";
        }
        if (exe.contains("witcher") || exe.contains("cyberpunk") || exe.contains("gwent")) {
            return "gog";
        }
        if (exe.contains("genshin") || exe.contains("yuanshen") || exe.contains("starrail")
                || exe.contains("zenless") || exe.contains("hkrpg")) {
            return "mihoyo";
        }
        if (exe.contains("cs2") || exe.contains("csgo") || exe.contains("dota2")
                || exe.contains("eldenring") || exe.contains("gta5") || exe.contains("rdr2")
                || exe.contains("forzahorizon")) {
            return "steam";
        }
        return "custom";
    }

    private static final Set<String> SYSTEM_PROCESSES = new HashSet<>(Arrays.asList(
            "explorer", "svchost", "system", "wininit", "winlogon", "dwm", "csrss", "services",
            "lsass", "smss", "fontdrvhost", "dllhost", "taskhostw", "runtimebroker", "sihost",
            "conhost", "audiodg", "spoolsv", "searchindexer", "ctfmon", "registry",
            "memorycompression", "shellexperiencehost", "startmenuexperiencehost", "textinputhost",
            "now-playing", "nowplayingservice", "nowplaying", "openjdk", "java", "javaw",
            "msedgewebview2", "msedge", "chrome", "firefox", "opera", "brave",
            "steam", "steamwebhelper", "steamservice", "wechat", "weixin", "qq", "qqnt", "tim",
            "obs64", "obs", "taskmgr", "devenv", "code", "manychat", "discord",
            "bar", "notepad", "winword", "excel", "powerpnt", "outlook", "dwm", "audiodg"
    ));

    /** 常见后台工具/软件（非游戏），避免误判。 */
    private static final Set<String> BACKGROUND_TOOLS = new HashSet<>(Arrays.asList(
            "rustdesk", "sunshine", "gamebar", "gamebartips", "onenote", "paint",
            "cmd", "powershell", "wmplayer", "mpc-hc", "potplayermini64", "vlc",
            "everything", "clash", "v2ray", "nvidiawebhelper", "nvcontainer",
            "steamwebhelper", "epicwebhelper", "epicgameslauncher", "gamelauncher",
            "battle.net", "battlewebhelper", "wegame", "wegamewebhelper",
            "goggalaxy", "ubisoftconnect", "uplay", "origin", "anticheat", "easyanticheat",
            "be", "battleye", "beservice", "vanguard", "vgc", "mihoyo", "hoyoplay"
    ));

    /** 返回当前运行的进程名列表（供前端配置进程映射时提示）。 */
    public List<String> runningProcesses() {
        List<String> list = new ArrayList<>();
        for (RunningProc p : enumerateProcesses()) {
            if (!isSystemProcess(p.exe)) {
                list.add(p.exe);
            }
        }
        Collections.sort(list);
        return list;
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
        // 不再内置硬编码游戏映射。识别完全交给：Steam 注册表 + 用户自定义 + 自动进程识别。
        s.setCustomGames(new HashMap<>());
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
