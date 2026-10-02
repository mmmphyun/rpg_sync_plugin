package com.rpgsync.rpgSyncPlugin.redis;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;

public class RedisPubSubListener extends JedisPubSub {
    private final RpgSyncPlugin plugin;

    public RedisPubSubListener(RpgSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMessage(String channel, String message) {
        if (message == null || message.trim().isEmpty()) return;

        // Switch to the Bukkit main thread to interact safely with the server
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Ultimate Guard: If global system is inactive, ignore all inbound PubSub events immediately
            if (!plugin.getRedisManager().isSystemActive()) {
                return;
            }
            try {
                String uuidStr = "";
                String extra = "";
                String cleanMessage = message.trim();

                if (cleanMessage.startsWith("{")) {
                    // Robust JSON payload parsing using Gson
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(cleanMessage).getAsJsonObject();
                    if (json.has("uuid")) {
                        uuidStr = json.get("uuid").getAsString().trim();
                    }
                    if (json.has("minecraft_username")) {
                        extra = json.get("minecraft_username").getAsString().trim();
                    } else if (json.has("reason")) {
                        extra = json.get("reason").getAsString().trim();
                    }
                } else {
                    // Fallback to legacy string parsing
                    uuidStr = cleanMessage;
                    if (cleanMessage.contains(":")) {
                        String[] parts = cleanMessage.split(":", 2);
                        uuidStr = parts[0].trim();
                        extra = parts[1].trim();
                    } else if (cleanMessage.contains(",")) {
                        String[] parts = cleanMessage.split(",", 2);
                        uuidStr = parts[0].trim();
                        extra = parts[1].trim();
                    }
                }

                UUID uuid = UUID.fromString(uuidStr);
                Player player = Bukkit.getPlayer(uuid);

                switch (channel) {
                    case "rpgsync:voice_leave" -> {
                        if (player != null && player.isOnline()) {
                            // Check bypass status asynchronously before starting the countdown timer
                            plugin.getDatabaseManager().selectUserBypass(uuid).thenAccept(dbBypass -> {
                                boolean isTempBypass = plugin.getRedisManager().checkTempBypass(uuid);
                                if (!dbBypass && !isTempBypass) {
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        plugin.getKickScheduler().startVoiceLeaveTimer(player, 60);
                                    });
                                }
                            });
                        }
                    }
                    case "rpgsync:bypass_granted" -> {
                        // Cancel timers and show restore title
                        plugin.getKickScheduler().handleRestore(uuid);
                        // Auto reset reason cooldown on successful staff bypass approval
                        plugin.getRedisManager().resetReasonCooldown(uuid);
                    }
                    case "rpgsync:kick_player" -> {
                        if (player != null && player.isOnline()) {
                            String kickReason = extra.isEmpty() ? "디스코드 음성 채널 연동 해제로 퇴장 처리되었습니다." : extra;
                            player.kickPlayer("§c[WHITELIST]\n\n" + kickReason);
                            plugin.getLogger().info("Kicked player " + player.getName() + " due to kick_player command.");
                        }
                    }
                    default -> plugin.getLogger().warning("Received message on unhandled channel: " + channel);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Received invalid UUID format from channel (" + channel + "): " + message);
            } catch (Exception e) {
                plugin.getLogger().severe("Error processing Redis pub/sub message: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onSubscribe(String channel, int subscribedChannels) {
        plugin.getLogger().info("Subscribed to channel: " + channel);
    }

    @Override
    public void onUnsubscribe(String channel, int subscribedChannels) {
        plugin.getLogger().info("Unsubscribed from channel: " + channel);
    }
}
