package com.rpgsync.rpgSyncPlugin.database;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import com.rpgsync.rpgSyncPlugin.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {
    private final RpgSyncPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(RpgSyncPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        initPool(config);
    }

    private void initPool(PluginConfig config) {
        try {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(config.getDbJdbcUrl());
            hikariConfig.setUsername(config.getDbUsername());
            hikariConfig.setPassword(config.getDbPassword());
            hikariConfig.setMaximumPoolSize(config.getDbMaximumPoolSize());
            hikariConfig.setConnectionTimeout(config.getDbConnectionTimeout());
            hikariConfig.setDriverClassName("org.postgresql.Driver");

            // Recommended HikariCP settings for PostgreSQL
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            this.dataSource = new HikariDataSource(hikariConfig);
            plugin.getLogger().info("Database (HikariCP) has been successfully initialized.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize HikariCP Database Connection Pool: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the bypass voice check status for a user.
     * Table: users, Columns: uuid (VARCHAR/UUID), bypass_voice_check (BOOLEAN)
     */
    public CompletableFuture<Boolean> selectUserBypass(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT bypass_voice_check FROM users WHERE minecraft_uuid = ?;";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, uuid.toString().toLowerCase());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("bypass_voice_check");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("SQL Error in selectUserBypass: " + e.getMessage());
            }
            return false;
        });
    }

    /**
     * Updates the player's minecraft username in the database.
     * Table: users, Columns: uuid (VARCHAR/UUID), minecraft_username (VARCHAR)
     */
    public CompletableFuture<Void> updateUsername(UUID uuid, String username) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE users SET minecraft_username = ? WHERE minecraft_uuid = ?;";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, username);
                stmt.setString(2, uuid.toString().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("SQL Error in updateUsername: " + e.getMessage());
            }
        });
    }

    /**
     * Gets the Korean nickname of the player from the database.
     * Table: users, Columns: uuid (VARCHAR/UUID), korean_nickname (VARCHAR)
     */
    public CompletableFuture<String> getKoreanNickname(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT nickname FROM users WHERE minecraft_uuid = ?;";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setString(1, uuid.toString().toLowerCase());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String nick = rs.getString("nickname");
                        return nick != null ? nick : "";
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("SQL Error in getKoreanNickname: " + e.getMessage());
            }
            return "";
        });
    }

    /**
     * Sets bypass voice check status for a user (Admin command helper).
     * Table: users, Columns: uuid (VARCHAR/UUID), bypass_voice_check (BOOLEAN)
     */
    public CompletableFuture<Void> setBypassVoiceCheck(UUID uuid, boolean bypass) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE users SET bypass_voice_check = ? WHERE minecraft_uuid = ?;";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                stmt.setBoolean(1, bypass);
                stmt.setString(2, uuid.toString().toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("SQL Error in setBypassVoiceCheck: " + e.getMessage());
            }
        });
    }

    /**
     * Retrieves all players registered as voice bypass in the database.
     */
    public CompletableFuture<List<String>> getBypassList() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> list = new ArrayList<>();
            String sql = "SELECT minecraft_username FROM users WHERE bypass_voice_check = true;";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("minecraft_username");
                    if (name != null && !name.isEmpty()) {
                        list.add(name);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("SQL Error in getBypassList: " + e.getMessage());
            }
            return list;
        });
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool closed successfully.");
        }
    }
}
