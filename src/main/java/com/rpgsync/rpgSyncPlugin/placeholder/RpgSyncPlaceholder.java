package com.rpgsync.rpgSyncPlugin.placeholder;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class RpgSyncPlaceholder extends PlaceholderExpansion {
    private final RpgSyncPlugin plugin;

    public RpgSyncPlaceholder(RpgSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rpgsync";
    }

    @Override
    public @NotNull String getAuthor() {
        return "RpgSync";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();

        switch (params.toLowerCase()) {
            case "korean_name" -> {
                // Fetch from connection-free cache for 0ms lookup speed
                String koreanName = plugin.getKoreanNameCache().get(uuid);
                return koreanName != null ? koreanName : (player.getName() != null ? player.getName() : "");
            }
            case "mc_name" -> {
                // Fetch from connection-free cache
                String mcName = plugin.getMcNameCache().get(uuid);
                return mcName != null ? mcName : (player.getName() != null ? player.getName() : "");
            }
            default -> {
                return null;
            }
        }
    }
}
