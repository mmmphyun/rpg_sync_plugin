package com.rpgsync.rpgSyncPlugin.redis;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import com.rpgsync.rpgSyncPlugin.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class RedisManager {
    private final RpgSyncPlugin plugin;
    private final PluginConfig config;
    private JedisPool jedisPool;
    private volatile boolean isRedisDisabled = false;
    private RedisPubSubListener pubSubListener;
    private Thread pubSubThread;

    private BukkitTask recoveryTask;
    private final Object recoveryLock = new Object();
    private int currentInterval = 30;

    public RedisManager(RpgSyncPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        initPool(config);
    }

    private void initPool(PluginConfig config) {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(16);
            poolConfig.setMaxIdle(8);
            poolConfig.setMinIdle(2);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);

            String host = config.getRedisHost();
            int port = config.getRedisPort();
            String password = config.getRedisPassword();
            int timeout = config.getRedisConnectionTimeout();

            if (password == null || password.trim().isEmpty()) {
                this.jedisPool = new JedisPool(poolConfig, host, port, timeout);
            } else {
                this.jedisPool = new JedisPool(poolConfig, host, port, timeout, password);
            }
            plugin.getLogger().info("Redis Connection Pool has been successfully initialized.");
        } catch (Exception e) {
            this.isRedisDisabled = true;
            plugin.getLogger().severe("Failed to initialize Redis Connection Pool: " + e.getMessage() + ". Fallback activated.");
            startRecoveryScheduler();
        }
    }

    public boolean isSystemActive() {
        if (isRedisDisabled) return true;
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get("rpgsync:system_active");
            if (value == null) {
                return true;
            }
            return Boolean.parseBoolean(value);
        } catch (Exception e) {
            handleRedisException(e);
            return true;
        }
    }

    public void setSystemActive(boolean active) {
        if (isRedisDisabled) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("rpgsync:system_active", String.valueOf(active));
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    public boolean isRedisDisabled() {
        return isRedisDisabled;
    }

    public void setRedisDisabled(boolean disabled) {
        this.isRedisDisabled = disabled;
    }

    private void handleRedisException(Exception e) {
        if (!isRedisDisabled) {
            isRedisDisabled = true;
            plugin.getLogger().severe("[Circuit Breaker] Redis connection failure! Circuit breaker triggered. Redis disabled. Falling back to DB: " + e.getMessage());
            startRecoveryScheduler();
        }
    }

    /**
     * Checks if the user has an active voice channel session in Discord.
     * Checks the presence of key "rpgsync:active_voice:{uuid}"
     */
    public boolean checkActiveVoice(UUID uuid) {
        if (isRedisDisabled) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.sismember("active_minecraft_users", uuid.toString().toLowerCase());
        } catch (Exception e) {
            handleRedisException(e);
            return false;
        }
    }

    /**
     * Controls the temporary bypass for a user.
     * key: rpgsync:temp_bypass:{uuid}
     * If add=true, sets the key with a TTL of 12 hours (43200 seconds).
     * If add=false, deletes the key.
     */
    public void tempBypass(UUID uuid, boolean add) {
        if (isRedisDisabled) return;
        String key = "rpgsync:temp_bypass:" + uuid.toString().toLowerCase();
        try (Jedis jedis = jedisPool.getResource()) {
            if (add) {
                jedis.setex(key, 43200, "true");
            } else {
                jedis.del(key);
            }
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    /**
     * Checks if a temporary bypass exists for the user.
     */
    public boolean checkTempBypass(UUID uuid) {
        if (isRedisDisabled) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists("rpgsync:temp_bypass:" + uuid.toString().toLowerCase());
        } catch (Exception e) {
            handleRedisException(e);
            return false;
        }
    }

    /**
     * Checks if /사유 command cooldown is active.
     */
    public boolean checkReasonCooldown(UUID uuid) {
        if (isRedisDisabled) return false;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists("rpgsync:reason_cooldown:" + uuid.toString().toLowerCase());
        } catch (Exception e) {
            handleRedisException(e);
            return false;
        }
    }

    /**
     * Sets /사유 cooldown with a 1-hour TTL (3600 seconds).
     */
    public void setReasonCooldown(UUID uuid) {
        if (isRedisDisabled) return;
        String key = "rpgsync:reason_cooldown:" + uuid.toString().toLowerCase();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, 3600, "true");
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    /**
     * Resets /사유 command cooldown by deleting its Redis key.
     */
    public void resetReasonCooldown(UUID uuid) {
        if (isRedisDisabled) return;
        String key = "rpgsync:reason_cooldown:" + uuid.toString().toLowerCase();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
            plugin.getLogger().info("Successfully reset reason cooldown for " + uuid);
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    /**
     * Retrieves the persistent user cache map from Redis.
     * key: rpgsync:user_mc:{uuid}
     */
    public Map<String, String> checkUserMcCache(UUID uuid) {
        if (isRedisDisabled) return Collections.emptyMap();
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll("rpgsync:user_mc:" + uuid.toString().toLowerCase());
        } catch (Exception e) {
            handleRedisException(e);
            return Collections.emptyMap();
        }
    }

    /**
     * Writes user mc caches in bulk.
     */
    public void warmUpCache(Map<UUID, Map<String, String>> data) {
        if (isRedisDisabled) return;
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            for (Map.Entry<UUID, Map<String, String>> entry : data.entrySet()) {
                String key = "rpgsync:user_mc:" + entry.getKey().toString().toLowerCase();
                pipeline.hset(key, entry.getValue());
            }
            pipeline.sync();
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    /**
     * Writes a single user mc cache to Redis.
     */
    public void warmUpCacheSingle(UUID uuid, Map<String, String> data) {
        if (isRedisDisabled) return;
        String key = "rpgsync:user_mc:" + uuid.toString().toLowerCase();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(key, data);
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    /**
     * Subscribes to the designated channels in a separate thread.
     */
    public void subscribeChannels() {
        if (isRedisDisabled) return;
        pubSubListener = new RedisPubSubListener(plugin);
        pubSubThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    plugin.getLogger().info("Subscribing to Redis channels: rpgsync:voice_leave, rpgsync:bypass_granted, rpgsync:kick_player");
                    jedis.subscribe(pubSubListener, "rpgsync:voice_leave", "rpgsync:bypass_granted", "rpgsync:kick_player");
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || isRedisDisabled) {
                        break;
                    }
                    plugin.getLogger().warning("Redis Pub/Sub connection lost. Retrying in 5 seconds... Error: " + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "RpgSync-RedisPubSub");
        pubSubThread.start();
    }

    /**
     * Publishes reason submission to the appropriate Redis channel.
     * Sends message as "uuid:minecraftName:reason" to channel "rpgsync:reason_submitted".
     */
    public void publishReason(UUID uuid, String minecraftName, String reason) {
        if (isRedisDisabled) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String message = uuid.toString().toLowerCase() + ":" + minecraftName + ":" + reason;
            jedis.publish("rpgsync:reason_submitted", message);
            plugin.getLogger().info("Published reason to rpgsync:reason_submitted: " + message);
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    /**
     * Flushes the redis database.
     */
    public void flushRedis() {
        if (isRedisDisabled) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
            plugin.getLogger().warning("Redis database flushed!");
        } catch (Exception e) {
            handleRedisException(e);
        }
    }

    public void startRecoveryScheduler() {
        synchronized (recoveryLock) {
            // Check if scheduler is already running or queued
            if (recoveryTask != null && !Bukkit.getScheduler().isCurrentlyRunning(recoveryTask.getTaskId()) && Bukkit.getScheduler().isQueued(recoveryTask.getTaskId())) {
                return;
            }
            if (recoveryTask != null) {
                recoveryTask.cancel();
            }
            currentInterval = 30; // Reset check interval
            scheduleNextRecoveryCheck();
        }
    }

    private void scheduleNextRecoveryCheck() {
        synchronized (recoveryLock) {
            if (!isRedisDisabled) {
                if (recoveryTask != null) {
                    recoveryTask.cancel();
                    recoveryTask = null;
                }
                return;
            }

            recoveryTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                boolean recovered = false;
                String host = config.getRedisHost();
                int port = config.getRedisPort();
                String password = config.getRedisPassword();

                // Ultra-short timeout PING via standalone Jedis instance
                try (Jedis tempJedis = new Jedis(host, port, 1000)) {
                    if (password != null && !password.trim().isEmpty()) {
                        tempJedis.auth(password);
                    }
                    String pong = tempJedis.ping();
                    if ("PONG".equalsIgnoreCase(pong)) {
                        recovered = true;
                    }
                } catch (Exception ignored) {}

                if (recovered) {
                    plugin.getLogger().info("[Circuit Breaker] Redis connection recovered! Re-enabling Redis.");
                    isRedisDisabled = false;
                    
                    // Re-subscribe channels
                    subscribeChannels();

                    synchronized (recoveryLock) {
                        recoveryTask = null;
                    }
                } else {
                    // Exponential backoff up to 300 seconds
                    synchronized (recoveryLock) {
                        currentInterval = Math.min(currentInterval * 2, 300);
                        plugin.getLogger().warning("[Circuit Breaker] Redis still unavailable. Retrying in " + currentInterval + " seconds...");
                        scheduleNextRecoveryCheck();
                    }
                }
            }, currentInterval * 20L); // Convert seconds to ticks (20 ticks = 1 second)
        }
    }

    public void close() {
        synchronized (recoveryLock) {
            if (recoveryTask != null) {
                recoveryTask.cancel();
                recoveryTask = null;
            }
        }
        if (pubSubListener != null) {
            try {
                pubSubListener.unsubscribe();
            } catch (Exception ignored) {}
        }
        if (pubSubThread != null) {
            pubSubThread.interrupt();
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            plugin.getLogger().info("Redis connection pool closed successfully.");
        }
    }
}
