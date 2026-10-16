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
import java.util.concurrent.CompletionException;

public final class RTP extends JavaPlugin implements Listener {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> countdowns = new HashMap<>();
    private final java.util.Set<UUID> searches = new java.util.HashSet<>();
    private final Map<UUID, BukkitTask> searchTimeouts = new HashMap<>();
    private final Map<UUID, UUID> searchTokens = new HashMap<>();
    private final Random random = new Random();
    private static final String GUI_PREFIX = ChatColor.DARK_PURPLE + "RTP Worlds | Page ";
    private static final String GUI_MAIN_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Control Center";
    private static final String GUI_SETTINGS_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Settings";
    private static final String GUI_WORLDS_TITLE = ChatColor.DARK_PURPLE + "RTP Worlds | Page ";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        validateConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RTP enabled.");
    }

    @Override
    public void onDisable() {
        countdowns.values().forEach(BukkitTask::cancel);
        searchTimeouts.values().forEach(BukkitTask::cancel);
        countdowns.clear();
        searchTimeouts.clear();
        searches.clear();
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
            validateConfig();
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
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitTask task = countdowns.remove(playerId);
        if (task != null) {
            task.cancel();
            // A cancelled countdown did not perform a teleport, so do not charge the cooldown.
            cooldowns.remove(playerId);
            event.getPlayer().sendMessage(message("countdown-cancelled"));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitTask task = countdowns.remove(playerId);
        if (task != null) {
            task.cancel();
            // Disconnecting during the countdown cancels the request, so refund its cooldown.
            cooldowns.remove(playerId);
        }
        finishSearch(playerId);
    }

    private void startTeleportSearch(Player player, World world) {
        if (!player.isOnline() || !searches.add(player.getUniqueId())) return;
        UUID playerId = player.getUniqueId();
        UUID searchToken = UUID.randomUUID();
        searchTokens.put(playerId, searchToken);
        int timeoutSeconds = Math.max(5, Math.min(120, getConfig().getInt("search-timeout-seconds", 20)));
        searchTimeouts.put(playerId, Bukkit.getScheduler().runTaskLater(this, () -> {
            if (finishSearch(playerId, searchToken)) sendRtpMessage(player, "timeout");
        }, timeoutSeconds * 20L));
        int maxActiveSearches = Math.max(1, Math.min(500, getConfig().getInt("maximum-active-searches", 25)));
        if (searches.size() > maxActiveSearches) {
            finishSearch(playerId, searchToken);
            sendRtpMessage(player, "busy");
            return;
        }
        sendRtpMessage(player, "searching");

        int minRadius = Math.max(0, getConfig().getInt("minimum-radius", 100));
        int maxRadius = Math.max(minRadius + 1, getConfig().getInt("maximum-radius", 5000));
        int attempts = Math.max(1, Math.min(200, getConfig().getInt("maximum-attempts", 20)));
        Location spawn = world.getSpawnLocation();
        int centerX = spawn.getBlockX();
        int centerZ = spawn.getBlockZ();
        String areaPath = "world-areas." + world.getName() + ".";
        boolean areaConfigured = getConfig().isConfigurationSection("world-areas." + world.getName());
        boolean customArea = getConfig().contains(areaPath + "min-x")
                && getConfig().contains(areaPath + "max-x")
                && getConfig().contains(areaPath + "min-z")
                && getConfig().contains(areaPath + "max-z");
        int minX = getConfig().getInt(areaPath + "min-x");
        int maxX = getConfig().getInt(areaPath + "max-x");
        int minZ = getConfig().getInt(areaPath + "min-z");
        int maxZ = getConfig().getInt(areaPath + "max-z");

        if (areaConfigured && !customArea) {
            finishSearch(playerId, searchToken);
            sendRtpMessage(player, "invalid-area");
            getLogger().warning("Incomplete RTP area configured for world '" + world.getName() + "'.");
            return;
        }
        if (customArea && (minX > maxX || minZ > maxZ)) {
            finishSearch(player.getUniqueId());
            sendRtpMessage(player, "invalid-area");
            getLogger().warning("Invalid RTP area configured for world '" + world.getName() + "'.");
            return;
        }
        if (!customArea && (minRadius < 0 || maxRadius <= minRadius)) {
            finishSearch(player.getUniqueId());
            sendRtpMessage(player, "invalid-area");
            getLogger().warning("Invalid RTP radius configuration. Check minimum-radius and maximum-radius.");
            return;
        }

        int[] candidates = new int[attempts * 2];
        for (int i = 0; i < attempts; i++) {
            if (customArea) {
                candidates[i * 2] = (int) random.nextLong((long) minX, (long) maxX + 1L);
                candidates[i * 2 + 1] = (int) random.nextLong((long) minZ, (long) maxZ + 1L);
            } else {
                double angle = random.nextDouble() * Math.PI * 2.0;
                // Square-root sampling distributes destinations more evenly across the ring's area.
                double minSquared = (double) minRadius * minRadius;
                double maxSquared = (double) maxRadius * maxRadius;
                double distance = Math.sqrt(minSquared + random.nextDouble() * (maxSquared - minSquared));
                candidates[i * 2] = centerX + (int) Math.round(Math.cos(angle) * distance);
                candidates[i * 2 + 1] = centerZ + (int) Math.round(Math.sin(angle) * distance);
            }
        }
        searchCandidate(player, world, candidates, attempts, 0, searchToken);
    }

    private void searchCandidate(Player player, World world, int[] candidates, int attempts, int index, UUID searchToken) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline()) {
            finishSearch(playerId, searchToken);
            return;
        }
        if (!isActiveSearch(playerId, searchToken)) return;
        if (index >= attempts) {
            finishSearch(player.getUniqueId(), searchToken);
            sendRtpMessage(player, "failed");
            return;
        }

        int x = candidates[index * 2];
        int z = candidates[index * 2 + 1];
        Location borderCheck = new Location(world, x + 0.5, world.getMinHeight(), z + 0.5);
        if (!world.getWorldBorder().isInside(borderCheck)) {
            searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
            return;
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            if (!getConfig().getBoolean("load-chunks", false)) {
                searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
                return;
            }
            // Paper async chunk loading avoids synchronously generating chunks on the main server thread.
            world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (error != null) {
                            Throwable cause = error instanceof CompletionException && error.getCause() != null
                                    ? error.getCause() : error;
                            getLogger().fine("Could not load RTP chunk " + chunkX + "," + chunkZ + " in "
                                    + world.getName() + ": " + cause.getMessage());
                            searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
                            return;
                        }
                        evaluateCandidate(player, world, x, z, candidates, attempts, index, searchToken);
                    }));
            return;
        }
        evaluateCandidate(player, world, x, z, candidates, attempts, index, searchToken);
    }

    private void evaluateCandidate(Player player, World world, int x, int z,
                                   int[] candidates, int attempts, int index, UUID searchToken) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline()) {
            finishSearch(playerId, searchToken);
            return;
        }
        if (!isActiveSearch(playerId, searchToken)) return;
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
                return;
            }
            Block ground = world.getHighestBlockAt(x, z);
            int groundY = ground.getY();
            if (groundY < world.getMinHeight() || groundY + 2 >= world.getMaxHeight()) {
                searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
                return;
            }
            Material groundType = ground.getType();
            Block feet = ground.getRelative(0, 1, 0);
            Block head = ground.getRelative(0, 2, 0);
            if (!groundType.isSolid() || isUnsafe(groundType)
                    || !feet.isPassable() || !head.isPassable()
                    || isUnsafe(feet.getType()) || isUnsafe(head.getType())
                    || feet.isLiquid() || head.isLiquid()) {
                searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
                return;
            }

            Location playerLocation = player.getLocation();
            Location destination = new Location(world, x + 0.5, groundY + 1.0, z + 0.5,
                    playerLocation.getYaw(), playerLocation.getPitch());
            player.teleportAsync(destination).whenComplete((success, error) ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (!finishSearch(player.getUniqueId(), searchToken) || !player.isOnline()) return;
                        if (error != null) {
                            getLogger().warning("RTP teleport failed for " + player.getName() + ": " + error.getMessage());
                            sendRtpMessage(player, "failed");
                            return;
                        }
                        if (!Boolean.TRUE.equals(success)) {
                            sendRtpMessage(player, "failed");
                            return;
                        }
                        player.sendMessage(message("success")
                                .replace("%x%", String.valueOf(destination.getBlockX()))
                                .replace("%y%", String.valueOf(destination.getBlockY()))
                                .replace("%z%", String.valueOf(destination.getBlockZ()))
                                .replace("%world%", world.getName()));
                    }));
        } catch (RuntimeException exception) {
            getLogger().fine("Skipping unsafe RTP candidate at " + x + ", " + z + ": " + exception.getMessage());
            searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
        }
    }

    private void openMainGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, GUI_MAIN_TITLE);
        fillGui(inventory);
        inventory.setItem(4, guiItem(Material.NETHER_STAR, "&d&lRTP CONTROL CENTER",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Manage random teleportation",
                "&7and configure the plugin.",
                "",
                "&fSelect an option below."));
        inventory.setItem(10, guiItem(Material.GRASS_BLOCK, "&a&lWORLD MANAGEMENT",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Configure which worlds allow RTP.",
                "",
                "&7Loaded worlds: &f" + Bukkit.getWorlds().size(),
                "",
                "&a▶ &fClick to manage worlds"));
        inventory.setItem(13, guiItem(Material.CLOCK, "&b&lTELEPORT SETTINGS",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Countdown: &f" + getConfig().getInt("countdown-seconds", 5) + " seconds",
                "&7Cooldown: &f" + getConfig().getInt("cooldown-seconds", 30) + " seconds",
                "&7Max attempts: &f" + getConfig().getInt("maximum-attempts", 20),
                "&7Chunk loading: " + (getConfig().getBoolean("load-chunks", false) ? "&aEnabled" : "&cDisabled"),
                "&7Avoid water: " + (getConfig().getBoolean("avoid-water", true) ? "&aEnabled" : "&cDisabled"),
                "",
                "&b▶ &fClick to edit settings"));
        inventory.setItem(16, guiItem(Material.WRITABLE_BOOK, "&e&lRELOAD CONFIGURATION",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Reload config.yml from disk.",
                "&7This discards external unsaved edits",
                "&7and reapplies validated settings.",
                "",
                "&e▶ &fClick to reload"));
        inventory.setItem(22, guiItem(Material.BARRIER, "&c&lCLOSE MENU",
                "&7Close the RTP Control Center."));
        player.openInventory(inventory);
    }

    private void openSettingsGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_SETTINGS_TITLE);
        fillGui(inventory);
        inventory.setItem(4, guiItem(Material.NETHER_STAR, "&d&lRTP SETTINGS",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Fine-tune RTP behavior and safety.",
                "&7Changes save automatically to config.yml.",
                "",
                "&fClick a control below to adjust it."));

        inventory.setItem(10, guiItem(Material.RED_DYE, "&c&lDECREASE COUNTDOWN",
                "&7Shorten the pre-teleport countdown.",
                "&c−1 second per click"));
        inventory.setItem(12, guiItem(Material.CLOCK, "&b&lCOUNTDOWN",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Current value: &f" + getConfig().getInt("countdown-seconds", 5) + " seconds",
                "&7Allowed range: &f1–60 seconds",
                "&8Time before teleport begins."));
        inventory.setItem(14, guiItem(Material.LIME_DYE, "&a&lINCREASE COUNTDOWN",
                "&7Lengthen the pre-teleport countdown.",
                "&a+1 second per click"));

        inventory.setItem(19, guiItem(Material.RED_DYE, "&c&lDECREASE COOLDOWN",
                "&7Reduce the wait between RTP uses.",
                "&c−5 seconds per click"));
        inventory.setItem(21, guiItem(Material.CLOCK, "&b&lCOOLDOWN",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Current value: &f" + getConfig().getInt("cooldown-seconds", 30) + " seconds",
                "&7Allowed range: &f0–86400 seconds",
                "&8Set to 0 to disable cooldown."));
        inventory.setItem(23, guiItem(Material.LIME_DYE, "&a&lINCREASE COOLDOWN",
                "&7Increase the wait between RTP uses.",
                "&a+5 seconds per click"));

        inventory.setItem(28, guiItem(Material.RED_DYE, "&c&lFEWER ATTEMPTS",
                "&7Try fewer candidate locations per request.",
                "&c−5 attempts per click"));
        inventory.setItem(30, guiItem(Material.PAPER, "&b&lMAXIMUM ATTEMPTS",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Current value: &f" + getConfig().getInt("maximum-attempts", 20),
                "&7Allowed range: &f1–200 attempts",
                "&8Maximum candidate locations per request."));
        inventory.setItem(32, guiItem(Material.LIME_DYE, "&a&lMORE ATTEMPTS",
                "&7Try more candidate locations per request.",
                "&a+5 attempts per click"));

        inventory.setItem(37, guiItem(Material.RED_DYE, "&c&lFEWER SEARCHES",
                "&7Lower the number of searches allowed at once.",
                "&c−5 searches per click"));
        inventory.setItem(39, guiItem(Material.BOOK, "&b&lCONCURRENT SEARCHES",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Current value: &f" + getConfig().getInt("maximum-active-searches", 25),
                "&7Allowed range: &f1–500 searches",
                "&8Limits simultaneous RTP searches."));
        inventory.setItem(41, guiItem(Material.LIME_DYE, "&a&lMORE SEARCHES",
                "&7Allow more searches to run at once.",
                "&a+5 searches per click"));

        inventory.setItem(46, guiItem(Material.RED_DYE, "&c&lSHORTER TIMEOUT",
                "&7Stop stalled searches sooner.",
                "&c−5 seconds per click"));
        inventory.setItem(48, guiItem(Material.CLOCK, "&b&lSEARCH TIMEOUT",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Current value: &f" + getConfig().getInt("search-timeout-seconds", 20) + " seconds",
                "&7Allowed range: &f5–120 seconds",
                "&8Maximum time allowed for a search."));
        inventory.setItem(50, guiItem(Material.LIME_DYE, "&a&lLONGER TIMEOUT",
                "&7Allow searches more time to finish.",
                "&a+5 seconds per click"));
        inventory.setItem(43, guiItem(getConfig().getBoolean("load-chunks", false) ? Material.LIME_DYE : Material.GRAY_DYE,
                "&b&lChunk Loading: " + (getConfig().getBoolean("load-chunks", false) ? "&aEnabled" : "&cDisabled"),
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Permit asynchronous loading or generation",
                "&7of destination chunks when required.",
                getConfig().getBoolean("load-chunks", false) ? "&aCurrently ON" : "&cCurrently OFF",
                "",
                "&e▶ Click to toggle"));
        inventory.setItem(44, guiItem(getConfig().getBoolean("avoid-water", true) ? Material.LIME_DYE : Material.GRAY_DYE,
                "&b&lAvoid Water: " + (getConfig().getBoolean("avoid-water", true) ? "&aEnabled" : "&cDisabled"),
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Reject water and bubble columns when",
                "&7checking candidate destinations.",
                getConfig().getBoolean("avoid-water", true) ? "&aCurrently ON" : "&cCurrently OFF",
                "",
                "&e▶ Click to toggle"));
        inventory.setItem(52, guiItem(Material.OAK_DOOR, "&d&lBACK TO MENU",
                "&7Return to the RTP Control Center.",
                "",
                "&d▶ Click to go back"));
        player.openInventory(inventory);
    }

    private void adjustSetting(Player player, int slot) {
        String path = null;
        int delta = 0;
        switch (slot) {
            case 10 -> { path = "countdown-seconds"; delta = -1; }
            case 14 -> { path = "countdown-seconds"; delta = 1; }
            case 19 -> { path = "cooldown-seconds"; delta = -5; }
            case 23 -> { path = "cooldown-seconds"; delta = 5; }
            case 28 -> { path = "maximum-attempts"; delta = -5; }
            case 32 -> { path = "maximum-attempts"; delta = 5; }
            case 37 -> { path = "maximum-active-searches"; delta = -5; }
            case 41 -> { path = "maximum-active-searches"; delta = 5; }
            case 46 -> { path = "search-timeout-seconds"; delta = -5; }
            case 50 -> { path = "search-timeout-seconds"; delta = 5; }
            default -> { return; }
        }
        int value = getConfig().getInt(path);
        int min = switch (path) {
            case "countdown-seconds" -> 1;
            case "cooldown-seconds" -> 0;
            case "maximum-attempts", "maximum-active-searches" -> 1;
            case "search-timeout-seconds" -> 5;
            default -> 0;
        };
        int max = switch (path) {
            case "countdown-seconds" -> 60;
            case "cooldown-seconds" -> 86400;
            case "maximum-attempts" -> 200;
            case "maximum-active-searches" -> 500;
            case "search-timeout-seconds" -> 120;
            default -> 0;
        };
        getConfig().set(path, Math.max(min, Math.min(max, value + delta)));
        saveConfig();
        openSettingsGui(player);
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
                    "&8━━━━━━━━━━━━━━━━━━━━",
                    "&7RTP Status: " + (enabled ? "&aEnabled" : "&cDisabled"),
                    "&7Environment: &f" + world.getEnvironment().name(),
                    "&7Players online: &f" + world.getPlayers().size(),
                    "",
                    "&8Action:",
                    enabled ? "&c▶ &fClick to disable RTP here" : "&a▶ &fClick to enable RTP here"));
        }

        inventory.setItem(45, guiItem(Material.ARROW, "&e&lPREVIOUS PAGE",
                "&7Browse the previous page of worlds.",
                page > 0 ? "&e▶ Click to go back" : "&8You are on the first page"));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&d&lBACK TO CONTROL CENTER",
                "&7Return to the RTP main menu.",
                "",
                "&d▶ Click to go back"));
        inventory.setItem(53, guiItem(Material.ARROW, "&e&lNEXT PAGE",
                "&7Browse the next page of worlds.",
                page < maxPage ? "&e▶ Click to continue" : "&8You are on the last page"));
        inventory.setItem(48, guiItem(Material.BOOK, "&b&lPAGE INFORMATION",
                "&8━━━━━━━━━━━━━━━━━━━━",
                "&7Page: &f" + (page + 1) + " &8/ &f" + (maxPage + 1),
                "&7Loaded worlds: &f" + worlds.size(),
                "",
                "&8Click a world to toggle its RTP status."));
        player.openInventory(inventory);
    }

    private void fillGui(Inventory inventory) {
        ItemStack darkFiller = guiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack accentFiller = guiItem(Material.PURPLE_STAINED_GLASS_PANE, " ");
        int size = inventory.getSize();
        int columns = 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / columns;
            int column = slot % columns;
            boolean border = row == 0 || column == 0 || column == columns - 1 || slot >= size - columns;
            inventory.setItem(slot, border && (row + column) % 2 == 0 ? accentFiller : darkFiller);
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
            if (slot == 10) {
                openWorldGui(player, 0);
            } else if (slot == 13) {
                openSettingsGui(player);
            } else if (slot == 16) {
                reloadConfig();
                validateConfig();
                player.sendMessage(message("reloaded"));
                openMainGui(player);
            } else if (slot == 22) {
                player.closeInventory();
            }
            return;
        }
        if (title.equals(GUI_SETTINGS_TITLE)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) {
                player.closeInventory();
                return;
            }
            int settingsSlot = event.getRawSlot();
            if (settingsSlot < 0 || settingsSlot >= event.getView().getTopInventory().getSize()) return;
            if (settingsSlot == 52) {
                openMainGui(player);
            } else if (settingsSlot == 43) {
                getConfig().set("load-chunks", !getConfig().getBoolean("load-chunks", false));
                saveConfig();
                openSettingsGui(player);
            } else if (settingsSlot == 44) {
                getConfig().set("avoid-water", !getConfig().getBoolean("avoid-water", true));
                saveConfig();
                openSettingsGui(player);
            } else {
                adjustSetting(player, settingsSlot);
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

    private boolean isUnsafe(Material material) {
        if (material == Material.LAVA || material == Material.MAGMA_BLOCK || material == Material.CACTUS
                || material == Material.FIRE || material == Material.SOUL_FIRE
                || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.POWDER_SNOW || material == Material.SWEET_BERRY_BUSH
                || material == Material.WITHER_ROSE || material == Material.VOID_AIR
                || material == Material.COBWEB || material == Material.POINTED_DRIPSTONE) {
            return true;
        }
        return getConfig().getBoolean("avoid-water", true)
                && (material == Material.WATER || material == Material.BUBBLE_COLUMN);
    }

    private void validateConfig() {
        int countdown = getConfig().getInt("countdown-seconds", 5);
        if (countdown < 1 || countdown > 60) {
            getLogger().warning("countdown-seconds must be between 1 and 60; using 5.");
            getConfig().set("countdown-seconds", 5);
        }

        long cooldown = getConfig().getLong("cooldown-seconds", 30);
        if (cooldown < 0 || cooldown > 86400) {
            getLogger().warning("cooldown-seconds must be between 0 and 86400; using 30.");
            getConfig().set("cooldown-seconds", 30);
        }

        int attempts = getConfig().getInt("maximum-attempts", 20);
        if (attempts < 1 || attempts > 200) {
            getLogger().warning("maximum-attempts must be between 1 and 200; using 20.");
            getConfig().set("maximum-attempts", 20);
        }

        int minRadius = getConfig().getInt("minimum-radius", 100);
        int maxRadius = getConfig().getInt("maximum-radius", 5000);
        if (minRadius < 0 || minRadius > 30_000_000 || maxRadius <= minRadius || maxRadius > 30_000_000) {
            getLogger().warning("Invalid RTP radius values; restoring minimum-radius=100 and maximum-radius=5000.");
            getConfig().set("minimum-radius", 100);
            getConfig().set("maximum-radius", 5000);
        }

        int maxSearches = getConfig().getInt("maximum-active-searches", 25);
        if (maxSearches < 1 || maxSearches > 500) {
            getLogger().warning("maximum-active-searches must be between 1 and 500; using 25.");
            getConfig().set("maximum-active-searches", 25);
        }

        int searchTimeout = getConfig().getInt("search-timeout-seconds", 20);
        if (searchTimeout < 5 || searchTimeout > 120) {
            getLogger().warning("search-timeout-seconds must be between 5 and 120; using 20.");
            getConfig().set("search-timeout-seconds", 20);
        }

        var areas = getConfig().getConfigurationSection("world-areas");
        if (areas != null) {
            for (String worldName : areas.getKeys(false)) {
                String path = "world-areas." + worldName + ".";
                if (!getConfig().contains(path + "min-x") || !getConfig().contains(path + "max-x")
                        || !getConfig().contains(path + "min-z") || !getConfig().contains(path + "max-z")) {
                    getLogger().warning("Incomplete world-areas entry for '" + worldName
                            + "'. All four min/max X/Z values are required.");
                    continue;
                }
                int minX = getConfig().getInt(path + "min-x");
                int maxX = getConfig().getInt(path + "max-x");
                int minZ = getConfig().getInt(path + "min-z");
                int maxZ = getConfig().getInt(path + "max-z");
                if (minX > maxX || minZ > maxZ) {
                    getLogger().warning("Invalid min/max coordinates for world-areas entry '" + worldName + "'.");
                }
            }
        }
        saveConfig();
    }

    private boolean isActiveSearch(UUID playerId, UUID searchToken) {
        return searches.contains(playerId) && searchToken.equals(searchTokens.get(playerId));
    }

    private boolean finishSearch(UUID playerId, UUID searchToken) {
        if (!searchToken.equals(searchTokens.get(playerId))) return false;
        return finishSearch(playerId);
    }

    private boolean finishSearch(UUID playerId) {
        boolean wasSearching = searches.remove(playerId);
        searchTokens.remove(playerId);
        BukkitTask timeout = searchTimeouts.remove(playerId);
        if (timeout != null) timeout.cancel();
        return wasSearching;
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