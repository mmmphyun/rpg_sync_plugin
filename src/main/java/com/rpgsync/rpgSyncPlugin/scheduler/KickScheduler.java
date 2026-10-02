package com.rpgsync.rpgSyncPlugin.scheduler;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KickScheduler {
    private final RpgSyncPlugin plugin;
    private final ConcurrentHashMap<UUID, BukkitTask> activeTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BukkitTask> timeoutTimers = new ConcurrentHashMap<>();

    public KickScheduler(RpgSyncPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts a 1-minute (60 seconds) voice leave countdown timer.
     * Displays a title message and actionbar countdown. Kicks on expiration.
     */
    public void startVoiceLeaveTimer(Player player, int seconds) {
        UUID uuid = player.getUniqueId();
        cancelTimers(uuid);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int remaining = seconds;

            @Override
            public void run() {
                if (!plugin.getRedisManager().isSystemActive()) {
                    cancelTimers(uuid);
                    return;
                }

                if (!player.isOnline()) {
                    cancelTimers(uuid);
                    return;
                }

                // Asynchronous Self-Healing check: Only query Redis cache (0ms cost, zero DB load)
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean isActive = plugin.getRedisManager().checkActiveVoice(uuid);
                    boolean isTemp = plugin.getRedisManager().checkTempBypass(uuid);
                    if (isActive || isTemp) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            handleRestore(uuid);
                        });
                    }
                });

                if (remaining <= 0) {
                    player.kickPlayer("§c[WHITELIST]\n\n디스코드 음성 채널 이탈 후 1분이 경과하여 퇴장 처리되었습니다.");
                    cancelTimers(uuid);
                    return;
                }

                // Show Title
                player.sendTitle("§c§l디스코드 음성 이탈 감지!", "§e음성 채널에 다시 접속해주세요. (남은 시간: " + remaining + "초)", 0, 35, 10);

                remaining--;
            }
        }, 0L, 20L);

        activeTimers.put(uuid, task);
    }

    /**
     * Starts a 5-minute (300 seconds) timeout timer when /사유 command is issued.
     * Displays a pending title message and actionbar countdown. Kicks on expiration.
     */
    public void startReasonTimeoutTimer(Player player, int seconds) {
        UUID uuid = player.getUniqueId();
        cancelTimers(uuid);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int remaining = seconds;

            @Override
            public void run() {
                if (!plugin.getRedisManager().isSystemActive()) {
                    cancelTimers(uuid);
                    return;
                }

                if (!player.isOnline()) {
                    cancelTimers(uuid);
                    return;
                }

                if (remaining <= 0) {
                    player.kickPlayer("§c[WHITELIST]\n\n사유 승인이 거절되었거나 연동 시간이 초과되어 퇴장 처리되었습니다.");
                    cancelTimers(uuid);
                    return;
                }

                // Show Title
                player.sendTitle("§e§l사유 처리 중", "§f관리자 승인 대기 중입니다. (제한 시간: " + remaining + "초)", 0, 35, 10);

                remaining--;
            }
        }, 0L, 20L);

        timeoutTimers.put(uuid, task);
    }

    /**
     * Cancels all voice-leave and reason timeout timers for the specified user, resetting warnings.
     */
    public void cancelTimers(UUID uuid) {
        BukkitTask activeTask = activeTimers.remove(uuid);
        if (activeTask != null) {
            activeTask.cancel();
        }

        BukkitTask timeoutTask = timeoutTimers.remove(uuid);
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
    }

    /**
     * Cancels timers and shows connection restored title.
     */
    public void handleRestore(UUID uuid) {
        cancelTimers(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendTitle("§a§l디스코드 음성 채널 연동 복구 완료", "§f정상적으로 접속이 유지됩니다.", 10, 40, 10);
        }
    }

    /**
     * Clears and cancels all active timers (used on plugin disable).
     */
    public void cancelAll() {
        for (UUID uuid : activeTimers.keySet()) {
            cancelTimers(uuid);
        }
        for (UUID uuid : timeoutTimers.keySet()) {
            cancelTimers(uuid);
        }
    }
}
