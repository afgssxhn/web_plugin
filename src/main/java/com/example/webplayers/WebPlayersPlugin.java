package com.example.webplayers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class WebPlayersPlugin extends JavaPlugin implements Listener {

    // GeoIP Cache
    private final Map<String, CachedCountry> countryCache = new ConcurrentHashMap<>();

    // Configuration values
    private String geoipApiUrl;
    private int geoipTimeout;
    private long cacheTtl;
    private boolean geoipEnabled;

    private int pingExcellent;
    private int pingGood;
    private int pingPoor;

    private int cpuLow;
    private int cpuMedium;
    private int cpuHigh;

    private boolean showCountry;
    private boolean showPing;
    private boolean showCpuUsage;
    private boolean showDiskSpace;

    // TPS Boss Bar Management
    private final Map<UUID, BossBar> activeTpsBars = new ConcurrentHashMap<>();
    private BukkitRunnable tpsUpdateTask = null;

    // TPS Configuration
    private int tpsUpdateInterval;
    private boolean tpsShowMspt;
    private double tpsExcellent;
    private double tpsGood;
    private double tpsPoor;

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Load configuration
        loadConfiguration();

        // Register event listener for TPS bars
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("WebPlayers plugin enabled!");
        getLogger().info("GeoIP caching enabled with TTL: " + cacheTtl + " seconds");
    }

    @Override
    public void onDisable() {
        // Stop TPS update task
        stopTpsUpdateTask();

        // Remove all active TPS boss bars
        for (BossBar bossBar : activeTpsBars.values()) {
            bossBar.removeAll();
        }
        activeTpsBars.clear();

        // Clear cache
        countryCache.clear();
        getLogger().info("WebPlayers plugin disabled!");
    }

    private void loadConfiguration() {
        // GeoIP settings
        geoipApiUrl = getConfig().getString("geoip.api-url", "https://ipapi.co/");
        geoipTimeout = getConfig().getInt("geoip.timeout", 3000);
        cacheTtl = getConfig().getLong("geoip.cache-ttl", 86400);
        geoipEnabled = getConfig().getBoolean("geoip.enabled", true);

        // Ping thresholds
        pingExcellent = getConfig().getInt("ping-colors.excellent", 50);
        pingGood = getConfig().getInt("ping-colors.good", 100);
        pingPoor = getConfig().getInt("ping-colors.poor", 200);

        // CPU thresholds
        cpuLow = getConfig().getInt("cpu-colors.low", 30);
        cpuMedium = getConfig().getInt("cpu-colors.medium", 60);
        cpuHigh = getConfig().getInt("cpu-colors.high", 85);

        // Features
        showCountry = getConfig().getBoolean("features.show-country", true);
        showPing = getConfig().getBoolean("features.show-ping", true);
        showCpuUsage = getConfig().getBoolean("features.show-cpu-usage", true);
        showDiskSpace = getConfig().getBoolean("features.show-disk-space", true);

        // TPS settings
        tpsUpdateInterval = getConfig().getInt("tps-settings.update-interval", 40);
        tpsShowMspt = getConfig().getBoolean("tps-settings.show-mspt", true);
        tpsExcellent = getConfig().getDouble("tps-settings.color-thresholds.excellent", 19.0);
        tpsGood = getConfig().getDouble("tps-settings.color-thresholds.good", 16.0);
        tpsPoor = getConfig().getDouble("tps-settings.color-thresholds.poor", 10.0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("webp")) {
            // Check permission
            if (!sender.hasPermission("web.players")) {
                sender.sendMessage(translateColor(getConfig().getString("messages.no-permission",
                    "&cYou don't have permission to use this command!")));
                return true;
            }

            if (!(sender instanceof Player)) {
                sender.sendMessage(translateColor(getConfig().getString("messages.console-only",
                    "This command can only be used by players!")));
                return true;
            }

            Player player = (Player) sender;
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

            if (onlinePlayers.isEmpty()) {
                player.sendMessage(translateColor(getConfig().getString("messages.no-players",
                    "&cNo players online!")));
                return true;
            }

            // Send header
            String header = translateColor(getConfig().getString("messages.players-header",
                "&6&l════════════"));
            String title = translateColor(getConfig().getString("messages.players-title",
                "&e&l  Online Players ({count}):").replace("{count}", String.valueOf(onlinePlayers.size())));

            player.sendMessage(header);
            player.sendMessage(title);
            player.sendMessage(header);

            // Collect player data asynchronously
            List<CompletableFuture<PlayerInfo>> futures = new ArrayList<>();

            for (Player onlinePlayer : onlinePlayers) {
                CompletableFuture<PlayerInfo> future = CompletableFuture.supplyAsync(() -> {
                    PlayerInfo info = new PlayerInfo();
                    info.name = onlinePlayer.getName();
                    info.ping = onlinePlayer.getPing();
                    info.ip = onlinePlayer.getAddress().getAddress().getHostAddress();

                    if (geoipEnabled && showCountry) {
                        info.country = getCountryFromIP(info.ip);
                    } else {
                        info.country = "N/A";
                    }

                    return info;
                });

                futures.add(future);
            }

            // Wait for all futures to complete and display in order
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    // Back to main thread for sending messages
                    Bukkit.getScheduler().runTask(this, () -> {
                        for (CompletableFuture<PlayerInfo> future : futures) {
                            try {
                                PlayerInfo info = future.get();
                                StringBuilder message = new StringBuilder();
                                message.append(ChatColor.DARK_GRAY).append("  ▪ ");
                                message.append(ChatColor.WHITE).append(ChatColor.BOLD).append(info.name);

                                if (showCountry) {
                                    message.append(ChatColor.DARK_GRAY).append(" | ");
                                    message.append(ChatColor.GREEN).append("🌍 ").append(info.country);
                                }

                                if (showPing) {
                                    message.append(ChatColor.DARK_GRAY).append(" | ");
                                    message.append(getPingColor(info.ping)).append("📡 ").append(info.ping).append("ms");
                                }

                                player.sendMessage(message.toString());
                            } catch (Exception e) {
                                getLogger().warning("Failed to get player info: " + e.getMessage());
                            }
                        }
                    });
                });

            return true;
        }

        if (command.getName().equalsIgnoreCase("webe")) {
            // Check permission
            if (!sender.hasPermission("web.equipment")) {
                sender.sendMessage(translateColor(getConfig().getString("messages.no-permission",
                    "&cYou don't have permission to use this command!")));
                return true;
            }

            if (!(sender instanceof Player)) {
                sender.sendMessage(translateColor(getConfig().getString("messages.console-only",
                    "This command can only be used by players!")));
                return true;
            }

            Player player = (Player) sender;

            // Send header
            String header = translateColor(getConfig().getString("messages.equipment-header",
                "&6&l════════════"));
            String title = translateColor(getConfig().getString("messages.equipment-title",
                "&e&l  Server Equipment:"));

            player.sendMessage(header);
            player.sendMessage(title);
            player.sendMessage(header);

            // Get system information
            Runtime runtime = Runtime.getRuntime();

            // OS
            String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                    ChatColor.AQUA + "💻 OS: " +
                    ChatColor.WHITE + os);

            // Architecture
            String arch = System.getProperty("os.arch");
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                    ChatColor.AQUA + "🔧 Arch: " +
                    ChatColor.WHITE + arch);

            // CPU Name
            String cpuName = getCPUName();
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                    ChatColor.AQUA + "⚙️ CPU: " +
                    ChatColor.WHITE + cpuName);

            // CPU Cores
            int processors = runtime.availableProcessors();
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                    ChatColor.AQUA + "🔄 Cores: " +
                    ChatColor.WHITE + processors);

            // CPU Usage - removed blocking Thread.sleep()
            if (showCpuUsage) {
                double cpuUsage = getSystemCPUUsage();
                ChatColor cpuColor = getCPUUsageColor(cpuUsage);
                player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                        ChatColor.AQUA + "📊 CPU Load: " +
                        cpuColor + String.format("%.1f%%", cpuUsage));
            }

            // RAM
            long maxMemory = runtime.maxMemory() / (1024 * 1024); // MB
            long totalMemory = runtime.totalMemory() / (1024 * 1024); // MB
            long freeMemory = runtime.freeMemory() / (1024 * 1024); // MB
            long usedMemory = totalMemory - freeMemory;

            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                    ChatColor.AQUA + "🧠 RAM: " +
                    ChatColor.WHITE + usedMemory + "MB" +
                    ChatColor.GRAY + " / " +
                    ChatColor.WHITE + maxMemory + "MB");

            // Disk Space
            if (showDiskSpace) {
                try {
                    java.io.File serverDir = new java.io.File(".");
                    long totalSpace = serverDir.getTotalSpace() / (1024 * 1024 * 1024); // GB
                    long usableSpace = serverDir.getUsableSpace() / (1024 * 1024 * 1024); // GB
                    long usedSpace = totalSpace - usableSpace;

                    player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                            ChatColor.AQUA + "💾 Disk: " +
                            ChatColor.WHITE + usedSpace + "GB" +
                            ChatColor.GRAY + " / " +
                            ChatColor.WHITE + totalSpace + "GB");
                } catch (Exception e) {
                    player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                            ChatColor.AQUA + "💾 Disk: " +
                            ChatColor.RED + "N/A");
                }
            }

            // Java Version
            String javaVersion = System.getProperty("java.version");
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " +
                    ChatColor.AQUA + "☕ Java: " +
                    ChatColor.WHITE + javaVersion);

            return true;
        }

        if (command.getName().equalsIgnoreCase("webtps")) {
            // Check permission
            if (!sender.hasPermission("web.tps")) {
                sender.sendMessage(translateColor(getConfig().getString("messages.no-permission",
                    "&cYou don't have permission to use this command!")));
                return true;
            }

            if (!(sender instanceof Player)) {
                sender.sendMessage(translateColor(getConfig().getString("messages.console-only",
                    "This command can only be used by players!")));
                return true;
            }

            Player player = (Player) sender;
            UUID playerId = player.getUniqueId();

            // Toggle logic
            if (activeTpsBars.containsKey(playerId)) {
                // Disable TPS display
                removeTpsBar(player);
                player.sendMessage(translateColor(getConfig().getString("messages.tps-disabled",
                    "&cТPS display disabled.")));
            } else {
                // Enable TPS display
                addTpsBar(player);
                player.sendMessage(translateColor(getConfig().getString("messages.tps-enabled",
                    "&aТPS display enabled! Boss bar will update every 2 seconds.")));
            }

            return true;
        }

        return false;
    }

    private String getCountryFromIP(String ip) {
        // If localhost - return Local
        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1") ||
            ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
            return "Local";
        }

        // Check cache first
        CachedCountry cached = countryCache.get(ip);
        if (cached != null && !cached.isExpired(cacheTtl)) {
            return cached.country;
        }

        try {
            // Use configured API URL
            URL url = new URL(geoipApiUrl + ip + "/country_name/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(geoipTimeout);
            conn.setReadTimeout(geoipTimeout);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String country = reader.readLine();
            reader.close();

            if (country != null && !country.isEmpty()) {
                // Cache the result
                countryCache.put(ip, new CachedCountry(country));
                return country;
            }

            return "Unknown";
        } catch (Exception e) {
            // Return cached value even if expired, better than nothing
            if (cached != null) {
                return cached.country;
            }
            return "Unknown";
        }
    }

    private ChatColor getPingColor(int ping) {
        if (ping < pingExcellent) return ChatColor.GREEN;
        if (ping < pingGood) return ChatColor.YELLOW;
        if (ping < pingPoor) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private String getCPUName() {
        try {
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                // Windows
                Process process = Runtime.getRuntime().exec("wmic cpu get name");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.toLowerCase().contains("name")) {
                        return line;
                    }
                }
            } else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                // Linux/Mac
                Process process = Runtime.getRuntime().exec("cat /proc/cpuinfo");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("model name")) {
                        return line.split(":")[1].trim();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
    }

    /**
     * Get system-wide CPU usage (non-blocking)
     * Uses OperatingSystemMXBean to get system load average
     */
    private double getSystemCPUUsage() {
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();

            // Try to get system CPU load (Java 7+)
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;
                double cpuLoad = sunOsBean.getCpuLoad();

                // getCpuLoad returns value between 0.0 and 1.0
                if (cpuLoad >= 0) {
                    return cpuLoad * 100.0;
                }
            }

            // Fallback: use load average (Unix/Linux only)
            double loadAverage = osBean.getSystemLoadAverage();
            if (loadAverage >= 0) {
                int processors = osBean.getAvailableProcessors();
                return (loadAverage / processors) * 100.0;
            }

        } catch (Exception e) {
            // Ignore
        }
        return 0.0;
    }

    private ChatColor getCPUUsageColor(double usage) {
        if (usage < cpuLow) return ChatColor.GREEN;
        if (usage < cpuMedium) return ChatColor.YELLOW;
        if (usage < cpuHigh) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private String translateColor(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Add TPS boss bar for a player
     */
    private void addTpsBar(Player player) {
        // Create boss bar with initial values
        BossBar bossBar = Bukkit.createBossBar(
            "Loading TPS data...",
            BarColor.GREEN,
            BarStyle.SOLID
        );

        bossBar.addPlayer(player);
        bossBar.setVisible(true);

        // Store in map
        activeTpsBars.put(player.getUniqueId(), bossBar);

        // Start update task if not already running
        startTpsUpdateTask();
    }

    /**
     * Remove TPS boss bar for a player
     */
    private void removeTpsBar(Player player) {
        UUID playerId = player.getUniqueId();
        BossBar bossBar = activeTpsBars.remove(playerId);

        if (bossBar != null) {
            bossBar.removeAll();
        }

        // Stop update task if no more players
        if (activeTpsBars.isEmpty()) {
            stopTpsUpdateTask();
        }
    }

    /**
     * Start the TPS update task (one task for all players)
     */
    private void startTpsUpdateTask() {
        // Don't start if already running
        if (tpsUpdateTask != null) {
            return;
        }

        tpsUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                // No players with active bars - shouldn't happen, but safety check
                if (activeTpsBars.isEmpty()) {
                    stopTpsUpdateTask();
                    return;
                }

                // Get TPS and MSPT from server
                double tps = Bukkit.getServer().getTPS()[0]; // 1-minute average
                double mspt = Bukkit.getServer().getAverageTickTime();

                // Format title
                String title = formatTpsTitle(tps, mspt);

                // Determine color based on TPS
                BarColor color = getTpsColor(tps);

                // Calculate progress (20.0 TPS = 100%, 0.0 TPS = 0%)
                double progress = Math.max(0.0, Math.min(1.0, tps / 20.0));

                // Update all active boss bars
                for (BossBar bossBar : activeTpsBars.values()) {
                    bossBar.setTitle(title);
                    bossBar.setColor(color);
                    bossBar.setProgress(progress);
                }
            }
        };

        // Schedule task: delay 0 (start immediately), period from config
        tpsUpdateTask.runTaskTimer(this, 0L, tpsUpdateInterval);
    }

    /**
     * Stop the TPS update task
     */
    private void stopTpsUpdateTask() {
        if (tpsUpdateTask != null) {
            tpsUpdateTask.cancel();
            tpsUpdateTask = null;
        }
    }

    /**
     * Format TPS title with color codes
     */
    private String formatTpsTitle(double tps, double mspt) {
        StringBuilder title = new StringBuilder();

        // TPS part with color
        ChatColor tpsColor = getTpsChatColor(tps);
        title.append(tpsColor).append("TPS: ").append(String.format("%.1f", tps));

        // MSPT part (if enabled)
        if (tpsShowMspt) {
            ChatColor msptColor = getMsptChatColor(mspt);
            title.append(ChatColor.DARK_GRAY).append(" | ");
            title.append(msptColor).append("MSPT: ").append(String.format("%.1f", mspt)).append("ms");
        }

        return title.toString();
    }

    /**
     * Get boss bar color based on TPS
     */
    private BarColor getTpsColor(double tps) {
        if (tps >= tpsExcellent) return BarColor.GREEN;
        if (tps >= tpsGood) return BarColor.YELLOW;
        if (tps >= tpsPoor) return BarColor.PINK;
        return BarColor.RED;
    }

    /**
     * Get chat color based on TPS (for title text)
     */
    private ChatColor getTpsChatColor(double tps) {
        if (tps >= tpsExcellent) return ChatColor.GREEN;
        if (tps >= tpsGood) return ChatColor.YELLOW;
        if (tps >= tpsPoor) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    /**
     * Get chat color based on MSPT
     * Ideal MSPT is < 50ms (for 20 TPS)
     */
    private ChatColor getMsptChatColor(double mspt) {
        if (mspt < 50) return ChatColor.GREEN;      // Excellent
        if (mspt < 75) return ChatColor.YELLOW;     // Good
        if (mspt < 100) return ChatColor.GOLD;      // Poor
        return ChatColor.RED;                        // Critical
    }

    /**
     * Handle player quit event - remove TPS bar
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Remove TPS bar if player has one
        if (activeTpsBars.containsKey(playerId)) {
            removeTpsBar(player);
        }
    }

    /**
     * Cached country information
     */
    private static class CachedCountry {
        final String country;
        final long timestamp;

        CachedCountry(String country) {
            this.country = country;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlSeconds) {
            long ageSeconds = (System.currentTimeMillis() - timestamp) / 1000;
            return ageSeconds > ttlSeconds;
        }
    }

    /**
     * Player information container
     */
    private static class PlayerInfo {
        String name;
        int ping;
        String ip;
        String country;
    }
}
