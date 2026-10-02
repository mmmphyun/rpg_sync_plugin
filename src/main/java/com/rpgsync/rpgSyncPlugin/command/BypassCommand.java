package com.rpgsync.rpgSyncPlugin.command;

import com.rpgsync.rpgSyncPlugin.RpgSyncPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class BypassCommand implements CommandExecutor, TabCompleter {
    private final RpgSyncPlugin plugin;

    public BypassCommand(RpgSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rpgsync.admin")) {
            sender.sendMessage("§c[WHITELIST] 이 명령어를 사용할 권한이 없습니다.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "bypass" -> {
                handleBypassSubCommand(sender, args);
            }
            case "redis" -> {
                handleRedisSubCommand(sender, args);
            }
            case "reload" -> {
                handleReloadSubCommand(sender);
            }
            case "toggle" -> {
                handleToggleSubCommand(sender, args);
            }
            case "cooldownreset", "쿨타임초기화" -> {
                handleCooldownResetSubCommand(sender, args);
            }
            default -> {
                sendHelp(sender);
            }
        }

        return true;
    }

    private void handleToggleSubCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[WHITELIST] 사용법: /rpgsync toggle <on/off>");
            return;
        }

        String action = args[1].toLowerCase();

        if (action.equals("on")) {
            sender.sendMessage("§a[WHITELIST] 글로벌 연동 활성화 처리 중...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.getRedisManager().setSystemActive(true);
                sender.sendMessage("§a[WHITELIST] 글로벌 연동 시스템이 활성화되었습니다.");
            });
        } else if (action.equals("off")) {
            sender.sendMessage("§a[WHITELIST] 글로벌 연동 비활성화 처리 중...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.getRedisManager().setSystemActive(false);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getKickScheduler().cancelAll();
                    sender.sendMessage("§a[WHITELIST] 글로벌 연동 시스템이 비활성화되었습니다. 모든 접속 제한 및 대기 타이머가 정지되었습니다.");
                });
            });
        } else {
            sender.sendMessage("§c[WHITELIST] 알 수 없는 옵션입니다. 사용법: /rpgsync toggle <on/off>");
        }
    }

    private void handleBypassSubCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[WHITELIST] 사용법: /rpgsync bypass <add/remove/list> [플레이어]");
            return;
        }

        String action = args[1].toLowerCase();

        if (action.equals("list")) {
            sender.sendMessage("§a[WHITELIST] 데이터베이스에서 예외(Bypass) 목록을 불러오는 중...");
            plugin.getDatabaseManager().getBypassList().thenAccept(list -> {
                if (list.isEmpty()) {
                    sender.sendMessage("§e[WHITELIST] 현재 예외 등록된 플레이어가 없습니다.");
                } else {
                    sender.sendMessage("§a[WHITELIST] 예외 대상자 (" + list.size() + "명): §f" + String.join(", ", list));
                }
            }).exceptionally(ex -> {
                sender.sendMessage("§c[WHITELIST] 목록을 불러오는 동안 오류가 발생했습니다.");
                return null;
            });
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("§c[WHITELIST] 플레이어 이름을 입력해주세요.");
            return;
        }

        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
        UUID uuid = targetPlayer.getUniqueId();

        if (action.equals("add")) {
            sender.sendMessage("§a[WHITELIST] " + targetName + "님을 예외 대상에 등록하는 중...");
            
            // 1. Database Update
            plugin.getDatabaseManager().setBypassVoiceCheck(uuid, true).thenRun(() -> {
                // 2. Redis Cache Update
                if (!plugin.getRedisManager().isRedisDisabled()) {
                    Map<String, String> cache = plugin.getRedisManager().checkUserMcCache(uuid);
                    if (cache == null || cache.isEmpty()) {
                        cache = new HashMap<>();
                        cache.put("minecraft_username", targetName);
                        cache.put("korean_nickname", targetName);
                    }
                    cache.put("bypass_voice_check", "true");
                    plugin.getRedisManager().warmUpCacheSingle(uuid, cache);
                }

                // 3. Immediately restore user in kick scheduler if active
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getKickScheduler().handleRestore(uuid);
                    sender.sendMessage("§a[WHITELIST] " + targetName + "님이 성공적으로 예외 대상으로 등록되었습니다.");
                });
            }).exceptionally(ex -> {
                sender.sendMessage("§c[WHITELIST] 등록 중 오류 발생: " + ex.getMessage());
                return null;
            });

        } else if (action.equals("remove")) {
            sender.sendMessage("§a[WHITELIST] " + targetName + "님을 예외 대상에서 제거하는 중...");
            
            // 1. Database Update
            plugin.getDatabaseManager().setBypassVoiceCheck(uuid, false).thenRun(() -> {
                // 2. Redis Cache Update
                if (!plugin.getRedisManager().isRedisDisabled()) {
                    Map<String, String> cache = plugin.getRedisManager().checkUserMcCache(uuid);
                    if (cache != null && !cache.isEmpty()) {
                        cache.put("bypass_voice_check", "false");
                        plugin.getRedisManager().warmUpCacheSingle(uuid, cache);
                    }
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§a[WHITELIST] " + targetName + "님이 예외 대상에서 제거되었습니다.");
                    
                    // If player is online, voice check should be re-evaluated
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            boolean isActive = plugin.getRedisManager().checkActiveVoice(uuid);
                            boolean isTemp = plugin.getRedisManager().checkTempBypass(uuid);
                            if (!isActive && !isTemp) {
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    plugin.getKickScheduler().startVoiceLeaveTimer(online, 60);
                                });
                            }
                        });
                    }
                });
            }).exceptionally(ex -> {
                sender.sendMessage("§c[WHITELIST] 제거 중 오류 발생: " + ex.getMessage());
                return null;
            });
        } else {
            sender.sendMessage("§c[WHITELIST] 알 수 없는 작업입니다. 사용법: /rpgsync bypass <add/remove/list>");
        }
    }

    private void handleRedisSubCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[WHITELIST] 사용법: /rpgsync redis <flush/reload>");
            return;
        }

        String action = args[1].toLowerCase();

        if (action.equals("flush")) {
            sender.sendMessage("§c[WHITELIST] Redis flush 실행 중...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.getRedisManager().flushRedis();
                sender.sendMessage("§a[WHITELIST] Redis DB가 완전히 초기화되었습니다.");
            });
        } else if (action.equals("reload")) {
            sender.sendMessage("§a[WHITELIST] Redis 커넥션 풀을 재설정하는 중...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                plugin.reloadRedisManager();
                sender.sendMessage("§a[WHITELIST] Redis 커넥션 풀이 재설정되었습니다.");
            });
        } else {
            sender.sendMessage("§c[WHITELIST] 알 수 없는 작업입니다. 사용법: /rpgsync redis <flush/reload>");
        }
    }

    private void handleReloadSubCommand(CommandSender sender) {
        sender.sendMessage("§a[WHITELIST] 플러그인 설정을 다시 로드하는 중...");
        plugin.reloadPlugin();
        sender.sendMessage("§a[WHITELIST] 설정 및 데이터베이스/Redis 연동이 리로드되었습니다.");
    }

    private void handleCooldownResetSubCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[WHITELIST] 사용법: /rpgsync cooldownreset <플레이어>");
            return;
        }

        String targetName = args[1];
        @SuppressWarnings("deprecation")
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
        UUID uuid = targetPlayer.getUniqueId();

        sender.sendMessage("§a[WHITELIST] " + targetName + "님의 사유 쿨타임을 초기화하는 중...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getRedisManager().resetReasonCooldown(uuid);
            sender.sendMessage("§a[WHITELIST] " + targetName + "님의 사유 입력 쿨타임이 성공적으로 초기화되었습니다.");
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§f=== §aWHITELIST 관리자 도움말 §f===");
        sender.sendMessage("§a/rpgsync bypass add <플레이어> §7- 예외 대상자로 추가");
        sender.sendMessage("§a/rpgsync bypass remove <플레이어> §7- 예외 대상자에서 제거");
        sender.sendMessage("§a/rpgsync bypass list §7- 예외 대상자 목록 조회");
        sender.sendMessage("§a/rpgsync redis flush §7- Redis DB 초기화 (비상 조치)");
        sender.sendMessage("§a/rpgsync redis reload §7- Redis 풀 재연결 및 서킷브레이커 복구");
        sender.sendMessage("§a/rpgsync toggle <on/off> §7- 글로벌 연동 활성화/비활성화 (비활성화 시 무소음 접속 허용)");
        sender.sendMessage("§a/rpgsync cooldownreset <플레이어> §7- 대상의 사유 입력 쿨타임 리셋");
        sender.sendMessage("§a/rpgsync reload §7- config.yml 및 전체 연동 리로드");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("rpgsync.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Arrays.asList("bypass", "redis", "reload", "toggle", "cooldownreset", "쿨타임초기화");
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("bypass")) {
                return Arrays.asList("add", "remove", "list");
            }
            if (args[0].equalsIgnoreCase("redis")) {
                return Arrays.asList("flush", "reload");
            }
            if (args[0].equalsIgnoreCase("toggle")) {
                return Arrays.asList("on", "off");
            }
            if (args[0].equalsIgnoreCase("cooldownreset") || args[0].equals("쿨타임초기화")) {
                List<String> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    players.add(p.getName());
                }
                return players;
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("bypass")) {
            if (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove")) {
                List<String> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    players.add(p.getName());
                }
                return players;
            }
        }

        return Collections.emptyList();
    }
}
