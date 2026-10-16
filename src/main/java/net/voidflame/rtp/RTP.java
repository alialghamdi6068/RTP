package net.voidflame.rtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class RTP extends JavaPlugin {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("RTP enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("rtp")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("rtp.admin")) {
                    sender.sendMessage(message("no-permission"));
                    return true;
                }
                reloadConfig();
                sender.sendMessage(color(getConfig().getString("messages.reloaded", "&aRTP configuration reloaded.")));
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cOnly players can use this command."));
                return true;
            }
            if (!player.hasPermission("rtp.use")) {
                player.sendMessage(message("no-permission"));
                return true;
            }
            if (player.hasPermission("rtp.bypasscooldown")) {
                teleportRandomly(player);
                return true;
            }

            long cooldownSeconds = Math.max(0, getConfig().getLong("cooldown-seconds", 60));
            long now = System.currentTimeMillis();
            long lastUse = cooldowns.getOrDefault(player.getUniqueId(), 0L);
            long remaining = cooldownSeconds - ((now - lastUse) / 1000L);
            if (remaining > 0) {
                player.sendMessage(message("cooldown").replace("%time%", String.valueOf(remaining)));
                return true;
            }

            cooldowns.put(player.getUniqueId(), now);
            teleportRandomly(player);
            return true;
        }
        return false;
    }

    private void teleportRandomly(Player player) {
        String configuredWorld = getConfig().getString("world", "").trim();
        World world = configuredWorld.isEmpty() ? player.getWorld() : Bukkit.getWorld(configuredWorld);
        if (world == null) {
            player.sendMessage(message("world-not-found"));
            return;
        }

        List<String> allowedWorlds = getConfig().getStringList("allowed-worlds");
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(player.getWorld().getName())) {
            player.sendMessage(message("world-not-allowed"));
            return;
        }

        int minRadius = Math.max(0, getConfig().getInt("minimum-radius", 100));
        int maxRadius = Math.max(minRadius + 1, getConfig().getInt("maximum-radius", 5000));
        int attempts = Math.max(1, getConfig().getInt("maximum-attempts", 20));

        player.sendMessage(message("searching"));
        BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // Only choose candidate coordinates asynchronously. Bukkit world/block access stays on the main thread.
            int x = 0;
            int z = 0;
            double distance = 0;
            for (int i = 0; i < attempts * 3; i++) {
                x = random.nextInt(maxRadius * 2 + 1) - maxRadius;
                z = random.nextInt(maxRadius * 2 + 1) - maxRadius;
                distance = Math.sqrt((double) x * x + (double) z * z);
                if (distance >= minRadius && distance <= maxRadius) break;
            }
            final int targetX = x;
            final int targetZ = z;
            Bukkit.getScheduler().runTask(this, () -> findSafeLocation(player, world, targetX, targetZ, attempts));
        });
    }

    private void findSafeLocation(Player player, World world, int x, int z, int attempts) {
        if (!player.isOnline()) return;

        int maxRadius = Math.max(1, getConfig().getInt("maximum-radius", 5000));
        int minRadius = Math.max(0, getConfig().getInt("minimum-radius", 100));
        for (int i = 0; i < attempts; i++) {
            int candidateX = i == 0 ? x : random.nextInt(maxRadius * 2 + 1) - maxRadius;
            int candidateZ = i == 0 ? z : random.nextInt(maxRadius * 2 + 1) - maxRadius;
            double distance = Math.sqrt((double) candidateX * candidateX + (double) candidateZ * candidateZ);
            if (distance < minRadius || distance > maxRadius) continue;

            Location base = new Location(world, candidateX + 0.5, world.getMaxHeight() - 1, candidateZ + 0.5);
            Block ground;
            try {
                // Do not force-load chunks by default; skip unloaded chunks to avoid unexpected lag.
                if (!world.isChunkLoaded(candidateX >> 4, candidateZ >> 4)) {
                    if (getConfig().getBoolean("load-chunks", false)) {
                        world.getChunkAt(candidateX >> 4, candidateZ >> 4);
                    } else {
                        continue;
                    }
                }
                ground = world.getHighestBlockAt(candidateX, candidateZ);
            } catch (RuntimeException exception) {
                continue;
            }

            Material groundType = ground.getType();
            if (!groundType.isSolid() || isUnsafe(groundType)) continue;
            Block feet = ground.getRelative(0, 1, 0);
            Block head = ground.getRelative(0, 2, 0);
            if (!feet.isPassable() || !head.isPassable()) continue;
            if (isUnsafe(feet.getType()) || isUnsafe(head.getType())) continue;

            Location destination = new Location(world, candidateX + 0.5, ground.getY() + 1.0, candidateZ + 0.5,
                    player.getLocation().getYaw(), player.getLocation().getPitch());
            player.teleportAsync(destination).thenAccept(success -> Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                if (success) {
                    player.sendMessage(message("success")
                            .replace("%x%", String.valueOf(destination.getBlockX()))
                            .replace("%y%", String.valueOf(destination.getBlockY()))
                            .replace("%z%", String.valueOf(destination.getBlockZ()))
                            .replace("%world%", world.getName()));
                } else {
                    player.sendMessage(message("failed"));
                }
            }));
            return;
        }
        player.sendMessage(message("failed"));
    }

    private boolean isUnsafe(Material material) {
        if (material == Material.LAVA || material == Material.MAGMA_BLOCK || material == Material.CACTUS
                || material == Material.FIRE || material == Material.SOUL_FIRE
                || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.POWDER_SNOW || material == Material.SWEET_BERRY_BUSH
                || material == Material.WITHER_ROSE || material == Material.VOID_AIR) {
            return true;
        }
        return getConfig().getBoolean("avoid-water", true)
                && (material == Material.WATER || material == Material.BUBBLE_COLUMN);
    }

    private String message(String key) {
        return color(getConfig().getString("messages." + key, "&cMessage not configured: " + key));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}