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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
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
    private final Map<UUID, BukkitTask> countdowns = new HashMap<>();
    private final Random random = new Random();
    private static final String GUI_PREFIX = ChatColor.DARK_PURPLE + "RTP Worlds | Page ";
    private static final String GUI_MAIN_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Control Center";
    private static final String GUI_WORLDS_TITLE = ChatColor.DARK_PURPLE + "RTP Worlds | Page ";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RTP enabled.");
    }

    @Override
    public void onDisable() {
        countdowns.values().forEach(BukkitTask::cancel);
        countdowns.clear();
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
                sender.sendMessage(message("admin-gui-players-only"));
                return true;
            }
            if (!player.hasPermission("rtp.admin")) {
                player.sendMessage(message("no-permission"));
                return true;
            }
            if (args[0].equalsIgnoreCase("worlds")) openWorldGui(player, 0);
            else openMainGui(player);
            return true;
        }

        if (args.length != 0) {
            sender.sendMessage(message("usage"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only"));
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

        if (countdowns.containsKey(player.getUniqueId())) {
            player.sendMessage(message("already-counting"));
            return true;
        }

        long cooldownSeconds = Math.max(0, getConfig().getLong("cooldown-seconds", 30));
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
        startCountdown(player, world);
        return true;
    }

    private void startCountdown(Player player, World world) {
        UUID id = player.getUniqueId();
        int seconds = Math.max(1, getConfig().getInt("countdown-seconds", 5));
        player.sendMessage(message("countdown-start").replace("%time%", String.valueOf(seconds)));
        final int[] remaining = {seconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || !countdowns.containsKey(id)) {
                BukkitTask active = countdowns.remove(id);
                if (active != null) active.cancel();
                return;
            }
            if (remaining[0] <= 1) {
                BukkitTask active = countdowns.remove(id);
                if (active != null) active.cancel();
                startTeleportSearch(player, world);
                return;
            }
            remaining[0]--;
            player.sendMessage(message("countdown-tick").replace("%time%", String.valueOf(remaining[0])));
        }, 20L, 20L);
        countdowns.put(id, task);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld().equals(to.getWorld()) && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        BukkitTask task = countdowns.remove(event.getPlayer().getUniqueId());
        if (task != null) {
            task.cancel();
            event.getPlayer().sendMessage(message("countdown-cancelled"));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        BukkitTask task = countdowns.remove(event.getPlayer().getUniqueId());
        if (task != null) task.cancel();
    }

    private void startTeleportSearch(Player player, World world) {
        if (!player.isOnline()) return;
        sendRtpMessage(player, "searching");
        int minRadius = Math.max(0, getConfig().getInt("minimum-radius", 100));
        int maxRadius = Math.max(minRadius + 1, getConfig().getInt("maximum-radius", 5000));
        int attempts = Math.max(1, getConfig().getInt("maximum-attempts", 20));
        Location spawn = world.getSpawnLocation();
        int centerX = spawn.getBlockX();
        int centerZ = spawn.getBlockZ();
        String areaPath = "world-areas." + world.getName() + ".";
        boolean customArea = getConfig().contains(areaPath + "min-x")
                && getConfig().contains(areaPath + "max-x")
                && getConfig().contains(areaPath + "min-z")
                && getConfig().contains(areaPath + "max-z");
        int minX = getConfig().getInt(areaPath + "min-x");
        int maxX = getConfig().getInt(areaPath + "max-x");
        int minZ = getConfig().getInt(areaPath + "min-z");
        int maxZ = getConfig().getInt(areaPath + "max-z");
        if (customArea && (minX > maxX || minZ > maxZ)) {
            player.sendMessage(message("invalid-area"));
            return;
        }
        int[] candidates = new int[attempts * 2];
        for (int i = 0; i < attempts; i++) {
            if (customArea) {
                candidates[i * 2] = minX + random.nextInt((int) Math.min(Integer.MAX_VALUE, (long) maxX - minX + 1));
                candidates[i * 2 + 1] = minZ + random.nextInt((int) Math.min(Integer.MAX_VALUE, (long) maxZ - minZ + 1));
            } else {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = minRadius + random.nextDouble() * (maxRadius - minRadius);
                candidates[i * 2] = centerX + (int) Math.round(Math.cos(angle) * distance);
                candidates[i * 2 + 1] = centerZ + (int) Math.round(Math.sin(angle) * distance);
            }
        }
        Bukkit.getScheduler().runTask(this, () -> findSafeLocation(player, world, candidates, attempts));
    }

    private void openMainGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, GUI_MAIN_TITLE);
        fillGui(inventory);
        inventory.setItem(4, guiItem(Material.COMPASS, "&d&lRTP CONTROL CENTER",
                "&7Manage random teleport settings.",
                "&8Paper 26.2 compatible plugin"));
        inventory.setItem(11, guiItem(Material.GRASS_BLOCK, "&a&lWorld Management",
                "&7Enable or disable RTP per world.",
                "&7Currently loaded: &f" + Bukkit.getWorlds().size() + " worlds",
                "",
                "&eClick to manage worlds"));
        inventory.setItem(13, guiItem(Material.CLOCK, "&b&lTeleport Settings",
                "&7Countdown: &f" + getConfig().getInt("countdown-seconds", 5) + " seconds",
                "&7Cooldown: &f" + getConfig().getInt("cooldown-seconds", 30) + " seconds",
                "&7Attempts: &f" + getConfig().getInt("maximum-attempts", 20),
                "&7Chunk loading: " + (getConfig().getBoolean("load-chunks", false) ? "&eEnabled" : "&aDisabled"),
                "",
                "&8Edit detailed values in config.yml"));
        inventory.setItem(15, guiItem(Material.WRITABLE_BOOK, "&e&lReload Configuration",
                "&7Reload config.yml from disk.",
                "&cUnsaved GUI changes are not applicable.",
                "",
                "&eClick to reload"));
        inventory.setItem(22, guiItem(Material.BARRIER, "&c&lClose"));
        player.openInventory(inventory);
    }

    private void openWorldGui(Player player, int page) {
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int maxPage = Math.max(0, (worlds.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_PREFIX + (page + 1));
        fillGui(inventory);
        List<String> disabled = getConfig().getStringList("disabled-worlds");

        int start = page * 45;
        int end = Math.min(start + 45, worlds.size());
        for (int i = start; i < end; i++) {
            World world = worlds.get(i);
            boolean enabled = !disabled.contains(world.getName());
            Material icon = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
            inventory.setItem(i - start, guiItem(icon,
                    (enabled ? "&a&lENABLED &8» &f" : "&c&lDISABLED &8» &f") + world.getName(),
                    "",
                    "&7RTP status: " + (enabled ? "&aEnabled" : "&cDisabled"),
                    "&7Environment: &f" + world.getEnvironment().name(),
                    "&7Players: &f" + world.getPlayers().size(),
                    "",
                    enabled ? "&eClick to disable RTP" : "&eClick to enable RTP"));
        }

        inventory.setItem(45, guiItem(Material.ARROW, "&e&lPrevious Page", "&7Go to the previous page."));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&d&lBack to Control Center", "&7Return to the main menu."));
        inventory.setItem(53, guiItem(Material.ARROW, "&e&lNext Page", "&7Go to the next page."));
        inventory.setItem(48, guiItem(Material.PAPER, "&bPage Information",
                "&7Page: &f" + (page + 1) + " / " + (maxPage + 1),
                "&7Loaded worlds: &f" + worlds.size(),
                "&7Click a world to toggle RTP."));
        player.openInventory(inventory);
    }

    private void fillGui(Inventory inventory) {
        ItemStack filler = guiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack guiItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(color(line));
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onWorldGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (title.equals(GUI_MAIN_TITLE)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) {
                player.closeInventory();
                return;
            }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            if (slot == 11) {
                openWorldGui(player, 0);
            } else if (slot == 15) {
                reloadConfig();
                player.sendMessage(message("reloaded"));
                openMainGui(player);
            } else if (slot == 22) {
                player.closeInventory();
            }
            return;
        }
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
            openMainGui(player);
            return;
        }
        if (slot == 45) {
            if (page > 0) openWorldGui(player, page - 1);
            return;
        }
        if (slot == 53) {
            if (page < Math.max(0, (Bukkit.getWorlds().size() - 1) / 45)) openWorldGui(player, page + 1);
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
                    if (!getConfig().getBoolean("load-chunks", false)) continue;
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
        sendRtpMessage(player, "failed");
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
        String prefix = getConfig().getString("messages.prefix", "&8[&5&lRTP&8] &r");
        String text = getConfig().getString("messages." + key, "&cMessage not configured: " + key);
        return color(prefix + text);
    }

    private void sendRtpMessage(Player player, String key) {
        if (player != null && player.isOnline()) player.sendMessage(message(key));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}