package com.rpgsync.rpgSyncPlugin.config;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class PluginConfig {
    private final RpgSyncPlugin plugin;

    // Database configurations
    private String dbJdbcUrl;
    private String dbUsername;
    private String dbPassword;
    private int dbMaximumPoolSize;
    private int dbConnectionTimeout;

    // Redis configurations
    private String redisHost;
    private int redisPort;
    private String redisPassword;
    private int redisConnectionTimeout;

    public PluginConfig(RpgSyncPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // Load Database settings
        this.dbJdbcUrl = config.getString("database.jdbc-url", "jdbc:postgresql://localhost:5432/postgres");
        this.dbUsername = config.getString("database.username", "postgres");
        this.dbPassword = config.getString("database.password", "");
        this.dbMaximumPoolSize = config.getInt("database.maximum-pool-size", 3);
        this.dbConnectionTimeout = config.getInt("database.connection-timeout", 5000);

        // Load Redis settings
        this.redisHost = config.getString("redis.host", "localhost");
        this.redisPort = config.getInt("redis.port", 6379);
        this.redisPassword = config.getString("redis.password", "");
        this.redisConnectionTimeout = config.getInt("redis.connection-timeout", 2000);
    }

    public String getDbJdbcUrl() {
        return dbJdbcUrl;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public int getDbMaximumPoolSize() {
        return dbMaximumPoolSize;
    }

    public int getDbConnectionTimeout() {
        return dbConnectionTimeout;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public int getRedisConnectionTimeout() {
        return redisConnectionTimeout;
    }
}
