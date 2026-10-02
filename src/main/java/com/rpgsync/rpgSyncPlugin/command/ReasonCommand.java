package com.rpgsync.rpgSyncPlugin.command;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class ReasonCommand implements CommandExecutor {
    private final RpgSyncPlugin plugin;

    public ReasonCommand(RpgSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§c[WHITELIST] 사용법: /사유 <내용>");
            return true;
        }

        UUID uuid = player.getUniqueId();

        // 1. Check if the /사유 command is under cooldown
        if (plugin.getRedisManager().checkReasonCooldown(uuid)) {
            player.sendMessage("§c[WHITELIST] 사유 등록 쿨타임 중입니다. (1시간당 1회 가능)");
            return true;
        }

        // 2. Gather full reasoning content
        String reason = String.join(" ", args);

        // 3. Register cooldown in Redis (1 hour TTL)
        plugin.getRedisManager().setReasonCooldown(uuid);

        // 4. Update Kick Scheduler -> Cancel current timer and start a 5-minute (300s) timeout timer
        plugin.getKickScheduler().cancelTimers(uuid);
        plugin.getKickScheduler().startReasonTimeoutTimer(player, 300);

        // 5. Publish the submitted reason to Redis pub/sub channel
        plugin.getRedisManager().publishReason(uuid, player.getName(), reason);

        player.sendMessage("§a[WHITELIST] 사유가 성공적으로 제출되었습니다. 관리자의 승인을 기다립니다 (5분 대기).");
        return true;
    }
}
