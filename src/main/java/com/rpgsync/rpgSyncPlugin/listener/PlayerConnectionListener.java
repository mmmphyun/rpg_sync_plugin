package com.rpgsync.rpgSyncPlugin.listener;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerConnectionListener implements Listener {
    private final RpgSyncPlugin plugin;

    public PlayerConnectionListener(RpgSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.getRedisManager().isSystemActive()) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String username = event.getName();

        boolean bypassVoiceCheck = false;
        boolean isTempBypass = false;
        boolean isActiveVoice = false;

        // Redis circuit breaker is active
        if (plugin.getRedisManager().isRedisDisabled()) {
            try {
                // Direct DB check (Synchronous via CompletableFuture.get since pre-login is async thread)
                bypassVoiceCheck = plugin.getDatabaseManager().selectUserBypass(uuid).get();
            } catch (Exception e) {
                plugin.getLogger().severe("Database fallback query failed during Redis outage: " + e.getMessage());
            }

            if (!bypassVoiceCheck) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                        "§c[WHITELIST]\n\n서버 통신 장애로 인해 예외(Bypass) 권한 보유자만 접속 가능합니다.");
            }
            return;
        }

        // Redis is healthy
        try {
            Map<String, String> cache = plugin.getRedisManager().checkUserMcCache(uuid);

            if (cache == null || cache.isEmpty()) {
                // Cache Miss -> Load from database to warm up cache
                String dbKoreanNick = plugin.getDatabaseManager().getKoreanNickname(uuid).get();

                if (dbKoreanNick == null || dbKoreanNick.trim().isEmpty()) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                            "§c[WHITELIST]\n\n연동되지 않은 사용자입니다. 디스코드에서 연동을 완료해주세요.");
                    return;
                }

                boolean dbBypass = plugin.getDatabaseManager().selectUserBypass(uuid).get();

                cache = new HashMap<>();
                cache.put("minecraft_username", username);
                cache.put("korean_nickname", dbKoreanNick);
                cache.put("bypass_voice_check", String.valueOf(dbBypass));

                // Save to Redis cache
                plugin.getRedisManager().warmUpCacheSingle(uuid, cache);
            } else {
                // Cache Hit -> Check username changes and update asynchronously
                String cachedUsername = cache.get("minecraft_username");
                if (cachedUsername == null || !cachedUsername.equals(username)) {
                    plugin.getDatabaseManager().updateUsername(uuid, username);
                    cache.put("minecraft_username", username);
                    plugin.getRedisManager().warmUpCacheSingle(uuid, cache);
                }
            }

            bypassVoiceCheck = Boolean.parseBoolean(cache.getOrDefault("bypass_voice_check", "false"));
            isTempBypass = plugin.getRedisManager().checkTempBypass(uuid);
            isActiveVoice = plugin.getRedisManager().checkActiveVoice(uuid);

            if (!bypassVoiceCheck && !isTempBypass && !isActiveVoice) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                        "§c[WHITELIST]\n\n디스코드 음성 채널에 접속한 상태로만 입장이 가능합니다.");
            } else {
                // Pre-warm local memory cache to eliminate duplicate Supabase DB queries on Join!
                String koreanNick = cache.getOrDefault("korean_nickname", username);
                plugin.getKoreanNameCache().put(uuid, koreanNick);
                plugin.getMcNameCache().put(uuid, username);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error during pre-login sync checks: " + e.getMessage());
            e.printStackTrace();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                    "§c[WHITELIST]\n\n연동 처리 과정에서 시스템 오류가 발생했습니다. 다시 접속해주세요.");
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        // Load pre-warmed Korean nickname from memory cache (Zero Supabase DB Load!)
        String koreanName = plugin.getKoreanNameCache().getOrDefault(uuid, player.getName());

        // For first-time join, format display name and tab list name using the specified style: &f:vanilla_rank_member: &e&l한글닉 &a닉네임
        if (!player.hasPlayedBefore()) {
            String rawFormat = "§f:vanilla_rank_member: §e§l" + koreanName + " §a" + username;
            
            // Delay execution by 2 ticks (100ms) to bypass other plugins' join processing
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                String formatted = rawFormat;
                if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                    formatted = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, rawFormat);
                }
                player.setDisplayName(formatted);
                player.setPlayerListName(formatted);
            }, 2L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Clear local ConcurrentHashMap cache
        plugin.getKoreanNameCache().remove(uuid);
        plugin.getMcNameCache().remove(uuid);

        // Cancel active KickScheduler timers
        plugin.getKickScheduler().cancelTimers(uuid);
    }
}
