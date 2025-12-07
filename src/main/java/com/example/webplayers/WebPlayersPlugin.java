package com.example.webplayers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class WebPlayersPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("WebPlayers plugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("WebPlayers plugin disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("webp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players!");
                return true;
            }

            Player player = (Player) sender;
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

            if (onlinePlayers.isEmpty()) {
                player.sendMessage(ChatColor.RED + "No players online!");
                return true;
            }

            // Заголовок
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "════════════");
            player.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "  Online Players (" + onlinePlayers.size() + "):");
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "════════════");

            // Для каждого игрока
            for (Player onlinePlayer : onlinePlayers) {
                final String playerName = onlinePlayer.getName();
                final int ping = onlinePlayer.getPing();
                final String ip = onlinePlayer.getAddress().getAddress().getHostAddress();

                // Асинхронно получаем страну
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    String country = getCountryFromIP(ip);
                    
                    // Возвращаемся в основной поток для отправки сообщения
                    Bukkit.getScheduler().runTask(WebPlayersPlugin.this, () -> {
                        String message = ChatColor.DARK_GRAY + "  ▪ " +
                                ChatColor.WHITE + "" + ChatColor.BOLD + playerName +
                                ChatColor.DARK_GRAY + " | " +
                                ChatColor.GREEN + "🌍 " + country +
                                ChatColor.DARK_GRAY + " | " +
                                getPingColor(ping) + "📡 " + ping + "ms";
                        
                        player.sendMessage(message);
                    });
                });
            }

            return true;
        }
        
        if (command.getName().equalsIgnoreCase("webe")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players!");
                return true;
            }

            Player player = (Player) sender;
            
            // Заголовок
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "════════════");
            player.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "  Server Equipment:");
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "════════════");
            
            // Получаем информацию о системе
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
            
            // CPU Name - попытка получить
            String cpuName = getCPUName();
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " + 
                    ChatColor.AQUA + "⚙️ CPU: " + 
                    ChatColor.WHITE + cpuName);
            
            // CPU Cores
            int processors = runtime.availableProcessors();
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " + 
                    ChatColor.AQUA + "🔄 Cores: " + 
                    ChatColor.WHITE + processors);
            
            // CPU Usage основного потока
            double cpuUsage = getServerThreadCPUUsage();
            ChatColor cpuColor = getCPUUsageColor(cpuUsage);
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " + 
                    ChatColor.AQUA + "📊 CPU Load: " + 
                    cpuColor + String.format("%.1f%%", cpuUsage));
            
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
            
            // Java Version
            String javaVersion = System.getProperty("java.version");
            player.sendMessage(ChatColor.DARK_GRAY + "  ▪ " + 
                    ChatColor.AQUA + "☕ Java: " + 
                    ChatColor.WHITE + javaVersion);

            return true;
        }
        
        return false;
    }

    private String getCountryFromIP(String ip) {
        // Если localhost - возвращаем Local
        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("192.168.") || ip.startsWith("10.")) {
            return "Local";
        }

        try {
            // Используем бесплатный API ipapi.co
            URL url = new URL("https://ipapi.co/" + ip + "/country_name/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String country = reader.readLine();
            reader.close();

            return country != null && !country.isEmpty() ? country : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private ChatColor getPingColor(int ping) {
        if (ping < 50) return ChatColor.GREEN;
        if (ping < 100) return ChatColor.YELLOW;
        if (ping < 200) return ChatColor.GOLD;
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
    
    private double getServerThreadCPUUsage() {
        try {
            java.lang.management.ThreadMXBean threadMXBean = 
                java.lang.management.ManagementFactory.getThreadMXBean();
            
            // Находим основной поток сервера
            long serverThreadId = -1;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().equals("Server thread")) {
                    serverThreadId = thread.getId();
                    break;
                }
            }
            
            if (serverThreadId == -1) {
                return 0.0;
            }
            
            // Получаем время CPU
            long cpuTime1 = threadMXBean.getThreadCpuTime(serverThreadId);
            long realTime1 = System.nanoTime();
            
            // Ждём немного
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return 0.0;
            }
            
            long cpuTime2 = threadMXBean.getThreadCpuTime(serverThreadId);
            long realTime2 = System.nanoTime();
            
            // Вычисляем процент
            long cpuTimeDiff = cpuTime2 - cpuTime1;
            long realTimeDiff = realTime2 - realTime1;
            
            if (realTimeDiff > 0) {
                return (cpuTimeDiff * 100.0) / realTimeDiff;
            }
            
        } catch (Exception e) {
            // Ignore
        }
        return 0.0;
    }
    
    private ChatColor getCPUUsageColor(double usage) {
        if (usage < 30) return ChatColor.GREEN;
        if (usage < 60) return ChatColor.YELLOW;
        if (usage < 85) return ChatColor.GOLD;
        return ChatColor.RED;
    }
}
