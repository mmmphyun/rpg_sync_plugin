package com.rpgsync.rpgSyncPlugin;

import com.rpgsync.rpgSyncPlugin.command.BypassCommand;
import com.rpgsync.rpgSyncPlugin.command.ReasonCommand;
import com.rpgsync.rpgSyncPlugin.config.PluginConfig;
import com.rpgsync.rpgSyncPlugin.database.DatabaseManager;
import com.rpgsync.rpgSyncPlugin.listener.PlayerConnectionListener;
import com.rpgsync.rpgSyncPlugin.placeholder.RpgSyncPlaceholder;
import com.rpgsync.rpgSyncPlugin.redis.RedisManager;
import com.rpgsync.rpgSyncPlugin.scheduler.KickScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RpgSyncPlugin extends JavaPlugin {
    private PluginConfig pluginConfig;
    private DatabaseManager databaseManager;
    private RedisManager redisManager;
    private KickScheduler kickScheduler;

    // 0ms connection-free cache for Placeholders
    private final ConcurrentHashMap<UUID, String> koreanNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> mcNameCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // 1. Save default config.yml
        saveDefaultConfig();

        // 2. Initialize Configurations and Managers
        this.pluginConfig = new PluginConfig(this);
        this.databaseManager = new DatabaseManager(this, pluginConfig);
        this.redisManager = new RedisManager(this, pluginConfig);
        this.kickScheduler = new KickScheduler(this);

        // 3. Register Event Listeners
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);

        // 4. Register Commands and Tab Completers
        if (getCommand("사유") != null) {
            getCommand("사유").setExecutor(new ReasonCommand(this));
        }
        BypassCommand bypassCommand = new BypassCommand(this);
        if (getCommand("rpgsync") != null) {
            getCommand("rpgsync").setExecutor(bypassCommand);
            getCommand("rpgsync").setTabCompleter(bypassCommand);
        }

        // 5. Hook into PlaceholderAPI if present
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RpgSyncPlaceholder(this).register();
            getLogger().info("PlaceholderAPI has been hooked successfully.");
        } else {
            getLogger().warning("PlaceholderAPI not found! Placeholders will not be registered.");
        }

        // 6. Start Redis Pub/Sub async listener thread
        redisManager.subscribeChannels();

        getLogger().info("RpgSyncPlugin has been successfully enabled.");
    }

    @Override
    public void onDisable() {
        // 1. Cancel and clear all running tasks/timers
        if (kickScheduler != null) {
            kickScheduler.cancelAll();
        }

        // 2. Safely close database connection pool
        if (databaseManager != null) {
            databaseManager.close();
        }

        // 3. Safely close Redis connections & interrupt sub thread
        if (redisManager != null) {
            redisManager.close();
        }

        getLogger().info("RpgSyncPlugin has been safely disabled.");
    }

    /**
     * Fully reloads the plugin settings, cancelling schedules, closing database and redis pools, and restarting them.
     */
    public void reloadPlugin() {
        if (kickScheduler != null) {
            kickScheduler.cancelAll();
        }
        if (redisManager != null) {
            redisManager.close();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }

        // Reload configuration
        reloadConfig();
        pluginConfig.loadConfig();

        // Re-initialize pools
        databaseManager = new DatabaseManager(this, pluginConfig);
        redisManager = new RedisManager(this, pluginConfig);
        kickScheduler = new KickScheduler(this);

        // Resume PubSub
        redisManager.subscribeChannels();
    }

    /**
     * Specifically reloads the Redis connection pool and attempts a recovery on circuit breaker state.
     */
    public void reloadRedisManager() {
        if (redisManager != null) {
            redisManager.close();
        }
        redisManager = new RedisManager(this, pluginConfig);
        redisManager.subscribeChannels();
    }

    // Getters
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public KickScheduler getKickScheduler() {
        return kickScheduler;
    }

    public ConcurrentHashMap<UUID, String> getKoreanNameCache() {
        return koreanNameCache;
    }

    public ConcurrentHashMap<UUID, String> getMcNameCache() {
        return mcNameCache;
    }
}
