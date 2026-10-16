package net.voidflame.rtp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class RTP extends JavaPlugin implements Listener {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Random random = new Random();
    private static final String GUI_PREFIX = ChatColor.DARK_PURPLE + "RTP Worlds | Page ";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RTP enabled for Paper 26.2.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rtp")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rtp.admin")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            reloadConfig();
            sender.sendMessage(message("reloaded"));
            return true;
        }

        if (args.length == 1 && (args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("worlds"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(color("&cOnly players can open the world GUI."));
                return true;
            }
            if (!player.hasPermission("rtp.admin")) {
                player.sendMessage(message("no-permission"));
                return true;
            }
            openWorldGui(player, 0);
            return true;
        }

        if (args.length != 0) {
            sender.sendMessage(color("&cUsage: /rtp [gui|reload]"));
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

        List<String> disabledWorlds = getConfig().getStringList("disabled-worlds");
        if (disabledWorlds.contains(player.getWorld().getName())) {
            player.sendMessage(message("world-disabled"));
            return true;
        }

        long cooldownSeconds = Math.max(0, getConfig().getLong("cooldown-seconds", 60));
        long now = System.currentTimeMillis();
        long lastUse = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        long remaining = cooldownSeconds - ((now - lastUse) / 1000L);
        if (!player.hasPermission("rtp.bypasscooldown") && remaining > 0) {
            player.sendMessage(message("cooldown").replace("%time%", String.valueOf(remaining)));
            return true;
        }

        String configuredWorld = getConfig().getString("world", "").trim();
        World world = configuredWorld.isEmpty() ? player.getWorld() : Bukkit.getWorld(configuredWorld);
        if (world == null) {
            player.sendMessage(message("world-not-found"));
            return true;
        }
        if (disabledWorlds.contains(world.getName())) {
            player.sendMessage(message("destination-world-disabled"));
            return true;
        }

        List<String> allowedWorlds = getConfig().getStringList("allowed-worlds");
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(player.getWorld().getName())) {
            player.sendMessage(message("world-not-allowed"));
            return true;
        }

        if (!player.hasPermission("rtp.bypasscooldown")) cooldowns.put(player.getUniqueId(), now);
        player.sendMessage(message("searching"));

        int minRadius = Math.max(0, getConfig().getInt("minimum-radius", 100));
        int maxRadius = Math.max(minRadius + 1, getConfig().getInt("maximum-radius", 5000));
        int attempts = Math.max(1, getConfig().getInt("maximum-attempts", 20));
        Location spawn = world.getSpawnLocation();
        int centerX = spawn.getBlockX();
        int centerZ = spawn.getBlockZ();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int[] candidates = new int[attempts * 2];
            for (int i = 0; i < attempts; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = minRadius + random.nextDouble() * (maxRadius - minRadius);
                candidates[i * 2] = centerX + (int) Math.round(Math.cos(angle) * distance);
                candidates[i * 2 + 1] = centerZ + (int) Math.round(Math.sin(angle) * distance);
            }
            Bukkit.getScheduler().runTask(this, () -> findSafeLocation(player, world, candidates, attempts));
        });
        return true;
    }

    private void openWorldGui(Player player, int page) {
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int maxPage = Math.max(0, (worlds.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_PREFIX + (page + 1));
        List<String> disabled = getConfig().getStringList("disabled-worlds");

        int start = page * 45;
        int end = Math.min(start + 45, worlds.size());
        for (int i = start; i < end; i++) {
            World world = worlds.get(i);
            boolean enabled = !disabled.contains(world.getName());
            ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.RED_DYE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(color((enabled ? "&aEnabled: &f" : "&cDisabled: &f") + world.getName()));
                meta.setLore(List.of(
                        color("&7Click to " + (enabled ? "&cdisable" : "&aenable") + " RTP in this world."),
                        color("&8World: " + world.getName())
                ));
                item.setItemMeta(meta);
            }
            inventory.setItem(i - start, item);
        }

        if (page > 0) inventory.setItem(45, guiItem(Material.ARROW, "&ePrevious page"));
        inventory.setItem(49, guiItem(Material.BARRIER, "&cClose"));
        if (page < maxPage) inventory.setItem(53, guiItem(Material.ARROW, "&eNext page"));
        player.openInventory(inventory);
    }

    private ItemStack guiItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onWorldGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.startsWith(GUI_PREFIX)) return;
        event.setCancelled(true);
        if (!player.hasPermission("rtp.admin")) {
            player.closeInventory();
            return;
        }
        int page;
        try {
            page = Integer.parseInt(title.substring(GUI_PREFIX.length())) - 1;
        } catch (NumberFormatException exception) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            openWorldGui(player, page - 1);
            return;
        }
        if (slot == 53) {
            openWorldGui(player, page + 1);
            return;
        }
        if (slot >= 45) return;

        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int worldIndex = page * 45 + slot;
        if (worldIndex < 0 || worldIndex >= worlds.size()) return;
        String worldName = worlds.get(worldIndex).getName();
        List<String> disabled = new ArrayList<>(getConfig().getStringList("disabled-worlds"));
        if (disabled.contains(worldName)) {
            disabled.remove(worldName);
            player.sendMessage(message("world-enabled").replace("%world%", worldName));
        } else {
            disabled.add(worldName);
            player.sendMessage(message("world-disabled-admin").replace("%world%", worldName));
        }
        getConfig().set("disabled-worlds", disabled);
        saveConfig();
        openWorldGui(player, page);
    }

    private void findSafeLocation(Player player, World world, int[] candidates, int attempts) {
        if (!player.isOnline()) return;
        for (int i = 0; i < attempts; i++) {
            int x = candidates[i * 2];
            int z = candidates[i * 2 + 1];
            try {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    if (!getConfig().getBoolean("load-chunks", true)) continue;
                    world.getChunkAt(x >> 4, z >> 4);
                }
                Block ground = world.getHighestBlockAt(x, z);
                Material groundType = ground.getType();
                if (!groundType.isSolid() || isUnsafe(groundType)) continue;
                Block feet = ground.getRelative(0, 1, 0);
                Block head = ground.getRelative(0, 2, 0);
                if (!feet.isPassable() || !head.isPassable()) continue;
                if (isUnsafe(feet.getType()) || isUnsafe(head.getType())) continue;

                Location playerLocation = player.getLocation();
                Location destination = new Location(world, x + 0.5, ground.getY() + 1.0, z + 0.5,
                        playerLocation.getYaw(), playerLocation.getPitch());
                player.teleportAsync(destination).thenAccept(success ->
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (!player.isOnline()) return;
                            player.sendMessage(message(success ? "success" : "failed")
                                    .replace("%x%", String.valueOf(destination.getBlockX()))
                                    .replace("%y%", String.valueOf(destination.getBlockY()))
                                    .replace("%z%", String.valueOf(destination.getBlockZ()))
                                    .replace("%world%", world.getName()));
                        }));
                return;
            } catch (RuntimeException exception) {
                getLogger().fine("Skipping unsafe RTP candidate at " + x + ", " + z + ": " + exception.getMessage());
            }
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