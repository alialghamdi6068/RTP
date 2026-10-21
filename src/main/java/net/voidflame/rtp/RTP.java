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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Sound;
import net.kyori.adventure.text.Component;
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
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class RTP extends JavaPlugin implements Listener {
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> countdowns = new HashMap<>();
    private final java.util.Set<UUID> searches = new java.util.HashSet<>();
    private final Map<UUID, BukkitTask> searchTimeouts = new HashMap<>();
    private final Map<UUID, UUID> searchTokens = new HashMap<>();
    private final Random random = new Random();
    private final java.util.Set<UUID> pendingTitleEdits = new java.util.HashSet<>();
    private final Map<UUID, String> pendingMessageEdits = new HashMap<>();
    private final Map<UUID, Integer> messageEditorPages = new HashMap<>();
    private File cooldownFile;
    private YamlConfiguration cooldownData;
    private static final String GUI_PREFIX = ChatColor.DARK_PURPLE + "RTP Worlds | Page ";
    private static final String GUI_MAIN_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Control Center";
    private static final String GUI_SETTINGS_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Settings";
    private static final String GUI_MESSAGES_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Message Editor | Page ";
    private static final String GUI_WORLD_SETTINGS_SELECT_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Select RTP World | Page ";
    private static final String GUI_WORLD_SETTINGS_PREFIX = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "World RTP | ";
    private static final String GUI_WORLD_AREA_PREFIX = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Area | ";
    private static final String GUI_WORLDS_TITLE = ChatColor.DARK_PURPLE + "RTP Worlds | Page ";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateLegacyDefaults();
        loadCooldowns();
        validateConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RTP enabled.");
    }

    private void migrateLegacyDefaults() {
        boolean changed = false;
        if (getConfig().getInt("config-version", 0) < 2) {
            // Upgrade only values that still match the previous plugin defaults; preserve custom settings.
            if (getConfig().getInt("countdown-seconds", -1) == 5) {
                getConfig().set("countdown-seconds", 0);
                changed = true;
            }
            if (getConfig().getInt("minimum-radius", -1) == 100) {
                getConfig().set("minimum-radius", 1000);
                changed = true;
            }
            if (getConfig().getInt("maximum-radius", -1) == 5000) {
                getConfig().set("maximum-radius", 50000);
                changed = true;
            }
            if (getConfig().getInt("maximum-attempts", -1) == 30) {
                getConfig().set("maximum-attempts", 200);
                changed = true;
            }
            if (!getConfig().getBoolean("load-chunks", true)) {
                getConfig().set("load-chunks", true);
                changed = true;
            }
            String menuTitle = getConfig().getString("menu.title", "");
            if ("&8&lRTP".equals(menuTitle) || "&8&lRandom Teleport".equals(menuTitle)) {
                getConfig().set("menu.title", "&8&lRANDOM TELEPORT");
                changed = true;
            }
            getConfig().set("config-version", 2);
            changed = true;
        }
        if (changed) {
            saveConfig();
            getLogger().info("Migrated RTP configuration to version 2 while preserving customized values.");
        }
    }

    @Override
    public void onDisable() {
        countdowns.values().forEach(BukkitTask::cancel);
        searchTimeouts.values().forEach(BukkitTask::cancel);
        countdowns.clear();
        searchTimeouts.clear();
        searches.clear();
        searchTokens.clear();
        pendingTitleEdits.clear();
        pendingMessageEdits.clear();
        messageEditorPages.clear();
        saveCooldowns();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("rtp")) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
            if (sender.hasPermission("rtp.admin")) {
                for (String option : List.of("admin", "gui", "worlds", "reload")) {
                    if (option.startsWith(prefix)) options.add(option);
                }
            }
            if (sender.hasPermission("rtp.use")) {
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)
                            && isWorldRtpEnabled(world)
                            && !getConfig().getStringList("disabled-worlds").contains(world.getName())) {
                        options.add(world.getName());
                    }
                }
            }
            return options.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(java.util.Locale.ROOT);
        boolean adminCommand = commandName.equals("adminrtp");
        if (!adminCommand && !commandName.equals("rtp")) return false;

        if (adminCommand || (args.length == 1 && args[0].equalsIgnoreCase("admin"))) {
            if (!sender.hasPermission("rtp.admin")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("admin-gui-players-only"));
                return true;
            }
            openMainGui(player);
            return true;
        }
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
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("players-only"));
                return true;
            }
            if (!player.hasPermission("rtp.use")) {
                player.sendMessage(message("no-permission"));
                return true;
            }
            openPlayerGui(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("worlds")) {
            if (!sender.hasPermission("rtp.admin")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("admin-gui-players-only"));
                return true;
            }
            openWorldGui(player, 0);
            return true;
        }
        if (args.length > 1) {
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
        if (args.length == 0) {
            openPlayerGui(player);
            return true;
        }
        String destination = args[0].toLowerCase(java.util.Locale.ROOT);
        World selectedWorld;
        if (destination.equals("overworld")) {
            selectedWorld = Bukkit.getWorlds().stream()
                    .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                    .filter(candidate -> candidate.getName().equalsIgnoreCase("world"))
                    .findFirst()
                    .orElseGet(() -> Bukkit.getWorlds().stream()
                            .filter(candidate -> candidate.getEnvironment() == World.Environment.NORMAL)
                            .findFirst().orElse(null));
        } else if (destination.equals("nether")) {
            selectedWorld = Bukkit.getWorlds().stream()
                    .filter(candidate -> candidate.getEnvironment() == World.Environment.NETHER)
                    .findFirst().orElse(null);
        } else if (destination.equals("end") || destination.equals("the_end")) {
            selectedWorld = Bukkit.getWorlds().stream()
                    .filter(candidate -> candidate.getEnvironment() == World.Environment.THE_END)
                    .findFirst().orElse(null);
        } else {
            selectedWorld = Bukkit.getWorld(args[0]);
        }
        if (selectedWorld == null) {
            player.sendMessage(message("world-not-found"));
            return true;
        }
        requestTeleport(player, selectedWorld);
        return true;
    }

    private void requestTeleport(Player player, World world) {
        if (!player.isOnline()) return;
        List<String> disabledWorlds = getConfig().getStringList("disabled-worlds");
        if (disabledWorlds.contains(player.getWorld().getName())) {
            player.sendMessage(message("world-disabled"));
            return;
        }
        if (disabledWorlds.contains(world.getName()) || !isWorldRtpEnabled(world)) {
            player.sendMessage(message("destination-world-disabled"));
            return;
        }
        List<String> allowedWorlds = getConfig().getStringList("allowed-worlds");
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(player.getWorld().getName())) {
            player.sendMessage(message("world-not-allowed"));
            return;
        }
        UUID playerId = player.getUniqueId();
        if (countdowns.containsKey(playerId) || searches.contains(playerId)) {
            player.sendMessage(message("already-counting"));
            return;
        }
        long cooldownSeconds = Math.max(0, worldLong(world, "cooldown-seconds",
                getConfig().getLong("cooldown-seconds", 30)));
        long now = System.currentTimeMillis();
        long lastUse = cooldowns.getOrDefault(playerId, 0L);
        long remaining = cooldownSeconds - ((now - lastUse) / 1000L);
        if (!player.hasPermission("rtp.bypasscooldown") && remaining > 0) {
            player.sendMessage(message("cooldown").replace("%time%", String.valueOf(remaining)));
            return;
        }
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.15f);
        startCountdown(player, world);
    }

    private String worldSettingPath(World world, String setting) {
        return "world-settings." + world.getName() + "." + setting;
    }

    private int worldInt(World world, String setting, int fallback) {
        return getConfig().getInt(worldSettingPath(world, setting), fallback);
    }

    private long worldLong(World world, String setting, long fallback) {
        return getConfig().getLong(worldSettingPath(world, setting), fallback);
    }

    private boolean isWorldRtpEnabled(World world) {
        return getConfig().getBoolean(worldSettingPath(world, "enabled"), true);
    }

    private List<World> getSelectableWorlds() {
        List<World> selectable = new ArrayList<>();
        List<String> disabled = getConfig().getStringList("disabled-worlds");
        for (String name : List.of("world", "world_nether", "world_the_end")) {
            World world = Bukkit.getWorld(name);
            if (world != null && !disabled.contains(name) && isWorldRtpEnabled(world)) selectable.add(world);
        }
        for (World world : Bukkit.getWorlds()) {
            if (!disabled.contains(world.getName()) && isWorldRtpEnabled(world)
                    && selectable.stream().noneMatch(existing -> existing.getName().equals(world.getName()))) {
                selectable.add(world);
            }
        }
        return selectable;
    }

    private String playerMenuTitle() {
        return color(getConfig().getString("menu.title", "&8&lRandom Teleport"));
    }

    private static final int PLAYER_MENU_PREVIOUS_SLOT_27 = 18;
    private static final int PLAYER_MENU_NEXT_SLOT_27 = 26;
    private static final int PLAYER_MENU_PREVIOUS_SLOT_54 = 45;
    private static final int PLAYER_MENU_NEXT_SLOT_54 = 53;

    private String playerMenuPageTitle(int page) {
        return page <= 0 ? playerMenuTitle()
                : playerMenuTitle() + ChatColor.DARK_GRAY + " | Page " + (page + 1);
    }

    private List<Integer> playerWorldMenuSlots(int menuSize, int page) {
        List<Integer> candidates = new ArrayList<>();
        int closeSlot = Math.max(0, Math.min(menuSize - 1, getConfig().getInt("menu.close-slot", menuSize == 27 ? 22 : 49)));
        int previous = menuSize == 27 ? PLAYER_MENU_PREVIOUS_SLOT_27 : PLAYER_MENU_PREVIOUS_SLOT_54;
        int next = menuSize == 27 ? PLAYER_MENU_NEXT_SLOT_27 : PLAYER_MENU_NEXT_SLOT_54;
        for (int slot = 0; slot < menuSize; slot++) {
            if (slot != closeSlot && slot != 4 && slot != previous && slot != next) candidates.add(slot);
        }

        List<World> worlds = getSelectableWorlds();
        int pageSize = candidates.size();
        int startIndex = Math.max(0, page) * pageSize;
        java.util.Set<Integer> used = new java.util.HashSet<>();
        List<Integer> ordered = new ArrayList<>();
        for (int i = startIndex; i < Math.min(worlds.size(), startIndex + pageSize); i++) {
            World world = worlds.get(i);
            int desired = getConfig().getInt("menu.world-slots." + world.getName(), defaultSlotForWorld(world));
            if (candidates.contains(desired) && used.add(desired)) ordered.add(desired);
            else {
                for (int candidate : candidates) {
                    if (used.add(candidate)) { ordered.add(candidate); break; }
                }
            }
        }
        for (int candidate : candidates) if (used.add(candidate)) ordered.add(candidate);
        return ordered;
    }

    private void openPlayerGui(Player player) {
        openPlayerGui(player, 0);
    }

    private void openPlayerGui(Player player, int requestedPage) {
        int configuredMenuSize = getConfig().getInt("menu.size", 27);
        int menuSize = configuredMenuSize == 54 ? 54 : 27;
        Inventory inventory = Bukkit.createInventory(null, menuSize, playerMenuPageTitle(Math.max(0, requestedPage)));
        if (getConfig().getBoolean("menu.filler", true)) fillGui(inventory);
        // Keep the player-facing selector minimal: the inventory title labels the menu,
        // and the dimension buttons carry the useful destination details.

        List<World> selectable = getSelectableWorlds();
        List<Integer> slots = playerWorldMenuSlots(menuSize, Math.max(0, requestedPage));
        int pageSize = slots.size();
        int maxPage = pageSize == 0 ? 0 : Math.max(0, (selectable.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        // Recreate with the clamped page so click handling always reflects the visible page.
        if (page != Math.max(0, requestedPage)) {
            inventory = Bukkit.createInventory(null, menuSize, playerMenuPageTitle(page));
            if (getConfig().getBoolean("menu.filler", true)) fillGui(inventory);
        }

        for (int i = 0; i < pageSize; i++) {
            int worldIndex = page * pageSize + i;
            if (worldIndex >= selectable.size()) break;
            World world = selectable.get(worldIndex);
            String name = switch (world.getEnvironment()) {
                case NETHER -> "NETHER";
                case THE_END -> "THE END";
                default -> world.getName().equalsIgnoreCase("world") ? "OVERWORLD" : world.getName().toUpperCase(java.util.Locale.ROOT);
            };
            String iconStyle = getConfig().getString("menu.icon-style", "DEFAULT").toUpperCase(java.util.Locale.ROOT);
            Material icon = Material.matchMaterial(getConfig().getString("menu.world-icons." + world.getName(), ""));
            if (icon == null || !icon.isItem()) icon = switch (iconStyle) {
                case "CLASSIC" -> switch (world.getEnvironment()) {
                    case NETHER -> Material.NETHER_BRICK;
                    case THE_END -> Material.ENDER_EYE;
                    default -> Material.COMPASS;
                };
                case "MINIMAL" -> switch (world.getEnvironment()) {
                    case NETHER -> Material.RED_CONCRETE;
                    case THE_END -> Material.PURPLE_CONCRETE;
                    default -> Material.LIME_CONCRETE;
                };
                default -> defaultWorldIcon(world);
            };
            inventory.setItem(slots.get(i), guiItem(icon, "&f&l" + name,
                    "&7Randomly teleport to this dimension.",
                    "",
                    "&a➜ Click to teleport"));
        }

        int closeSlot = Math.max(0, Math.min(menuSize - 1, getConfig().getInt("menu.close-slot", 22)));
        inventory.setItem(closeSlot, guiItem(Material.BARRIER, "&c&lCLOSE", "&7Close the RTP menu."));
        if (maxPage > 0) {
            int previousSlot = menuSize <= 27 ? PLAYER_MENU_PREVIOUS_SLOT_27 : PLAYER_MENU_PREVIOUS_SLOT_54;
            int nextSlot = menuSize <= 27 ? PLAYER_MENU_NEXT_SLOT_27 : PLAYER_MENU_NEXT_SLOT_54;
            if (page > 0) inventory.setItem(previousSlot, guiItem(Material.ARROW, "&d&lPREVIOUS PAGE",
                    "&7Page &f" + page + " &7of &f" + (maxPage + 1)));
            if (page < maxPage) inventory.setItem(nextSlot, guiItem(Material.ARROW, "&b&lNEXT PAGE",
                    "&7Page &f" + (page + 2) + " &7of &f" + (maxPage + 1)));
        }
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.1f);
    }

    private void startCountdown(Player player, World world) {
        UUID id = player.getUniqueId();
        int seconds = Math.max(0, worldInt(world, "countdown-seconds",
                getConfig().getInt("countdown-seconds", 0)));
        if (seconds == 0) {
            startTeleportSearch(player, world);
            return;
        }
        player.sendMessage(message("countdown-start").replace("%time%", String.valueOf(seconds)));
        player.sendActionBar(Component.text("Random teleport in " + seconds + "s"));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.0f);
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
            player.sendActionBar(Component.text("Random teleport in " + remaining[0] + "s"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.45f, 1.0f + (seconds - remaining[0]) * 0.08f);
        }, 20L, 20L);
        countdowns.put(id, task);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        // Cancel for any actual positional movement, including movement within a block.
        // Looking around alone does not cancel the countdown.
        if (from.getWorld().equals(to.getWorld())
                && Double.compare(from.getX(), to.getX()) == 0
                && Double.compare(from.getY(), to.getY()) == 0
                && Double.compare(from.getZ(), to.getZ()) == 0) return;
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitTask task = countdowns.remove(playerId);
        if (task != null) {
            task.cancel();
            event.getPlayer().sendActionBar(Component.text("Random teleport cancelled"));
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            event.getPlayer().sendMessage(message("countdown-cancelled"));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitTask task = countdowns.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        finishSearch(playerId);
    }

    private void startTeleportSearch(Player player, World world) {
        if (!player.isOnline()) return;

        // Revalidate the request after the countdown: administrators may have changed
        // world availability or configuration while the player was waiting.
        World loadedWorld = Bukkit.getWorld(world.getUID());
        if (loadedWorld == null || !loadedWorld.equals(world)) {
            sendRtpMessage(player, "world-not-found");
            return;
        }
        List<String> disabledWorlds = getConfig().getStringList("disabled-worlds");
        if (disabledWorlds.contains(player.getWorld().getName())) {
            sendRtpMessage(player, "world-disabled");
            return;
        }
        if (disabledWorlds.contains(world.getName()) || !isWorldRtpEnabled(world)) {
            sendRtpMessage(player, "destination-world-disabled");
            return;
        }
        List<String> allowedWorlds = getConfig().getStringList("allowed-worlds");
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(player.getWorld().getName())) {
            sendRtpMessage(player, "world-not-allowed");
            return;
        }
        if (!player.hasPermission("rtp.use")) {
            sendRtpMessage(player, "no-permission");
            return;
        }
        if (!searches.add(player.getUniqueId())) return;
        UUID playerId = player.getUniqueId();
        UUID searchToken = UUID.randomUUID();
        searchTokens.put(playerId, searchToken);
        int timeoutSeconds = Math.max(5, Math.min(120, worldInt(world, "search-timeout-seconds",
                getConfig().getInt("search-timeout-seconds", 20))));
        searchTimeouts.put(playerId, Bukkit.getScheduler().runTaskLater(this, () -> {
            if (finishSearch(playerId, searchToken)) sendRtpMessage(player, "timeout");
        }, timeoutSeconds * 20L));
        int maxActiveSearches = Math.max(1, Math.min(500, getConfig().getInt("maximum-active-searches", 25)));
        if (searches.size() > maxActiveSearches) {
            finishSearch(playerId, searchToken);
            sendRtpMessage(player, "busy");
            return;
        }

        int minRadius = Math.max(0, worldInt(world, "minimum-radius", getConfig().getInt("minimum-radius", 1000)));
        int maxRadius = Math.max(minRadius + 1, Math.min(30_000_000,
                worldInt(world, "maximum-radius", getConfig().getInt("maximum-radius", 50_000))));
        int attempts = Math.max(1, Math.min(200, worldInt(world, "maximum-attempts", getConfig().getInt("maximum-attempts", 200))));
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
                    runOnMainIfEnabled(() -> {
                        if (!isActiveSearch(player.getUniqueId(), searchToken)) return;
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

            Location playerLocation = player.getLocation();
            Location destination = null;
            int minimumY = Math.max(world.getMinHeight(), worldInt(world, "minimum-y", world.getMinHeight()));
            int maximumY = Math.min(world.getMaxHeight() - 2, worldInt(world, "maximum-y", world.getMaxHeight() - 2));
            boolean avoidWater = getConfig().getBoolean("avoid-water", true);

            // Search the selected column first, then nearby columns in the same loaded chunk.
            // This helps when the random coordinate lands in a crater, a narrow hole, or damaged terrain.
            for (int radius = 0; radius <= 7 && destination == null; radius++) {
                for (int dx = -radius; dx <= radius && destination == null; dx++) {
                    for (int dz = -radius; dz <= radius && destination == null; dz++) {
                        if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                        int candidateX = x + dx;
                        int candidateZ = z + dz;
                        if ((candidateX >> 4) != (x >> 4) || (candidateZ >> 4) != (z >> 4)) continue;

                        Location borderCheck = new Location(world, candidateX + 0.5, world.getMinHeight(), candidateZ + 0.5);
                        if (!world.getWorldBorder().isInside(borderCheck)) continue;

                        Block ground = world.getHighestBlockAt(candidateX, candidateZ);
                        int groundY = ground.getY();
                        if (groundY < minimumY || groundY > maximumY || groundY + 2 >= world.getMaxHeight()) continue;

                        Material groundType = ground.getType();
                        Block feet = ground.getRelative(0, 1, 0);
                        Block head = ground.getRelative(0, 2, 0);
                        if (!groundType.isSolid() || isUnsafe(groundType)
                                || !feet.isPassable() || !head.isPassable()
                                || isUnsafe(feet.getType()) || isUnsafe(head.getType())
                                || (avoidWater && (feet.isLiquid() || head.isLiquid()))) continue;

                        destination = new Location(world, candidateX + 0.5, groundY + 1.0, candidateZ + 0.5,
                                playerLocation.getYaw(), playerLocation.getPitch());
                    }
                }
            }

            if (destination == null) {
                searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
                return;
            }

            Location safeDestination = destination;
            player.teleportAsync(safeDestination).whenComplete((success, error) ->
                    runOnMainIfEnabled(() -> {
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
                        if (!player.hasPermission("rtp.bypasscooldown")) {
                            cooldowns.put(playerId, System.currentTimeMillis());
                            saveCooldowns();
                        }
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
                    }));
        } catch (RuntimeException exception) {
            getLogger().fine("Skipping unsafe RTP candidate at " + x + ", " + z + ": " + exception.getMessage());
            searchCandidate(player, world, candidates, attempts, index + 1, searchToken);
        }
    }

    private void openMainGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, GUI_MAIN_TITLE);
        fillGui(inventory);

        inventory.setItem(4, guiItem(Material.COMPARATOR, "&5&lRTP ADMIN",
                "&8&m--------------------",
                "&7Manage RTP settings and destination worlds.",
                "&7Loaded worlds: &f" + Bukkit.getWorlds().size(),
                "&7Active searches: &f" + searches.size(),
                "",
                "&8Select an action below."));

        inventory.setItem(10, guiItem(Material.GRASS_BLOCK, "&a&lWORLD MANAGEMENT",
                "&8&m--------------------",
                "&7Enable or disable RTP per loaded world.",
                "",
                "&a▶ Open world management"));

        inventory.setItem(12, guiItem(Material.REPEATER, "&d&lGLOBAL SETTINGS",
                "&8&m--------------------",
                "&7Default radius: &f" + getConfig().getInt("minimum-radius", 1000)
                        + "–" + getConfig().getInt("maximum-radius", 50000),
                "&7Cooldown: &f" + getConfig().getInt("cooldown-seconds", 30) + "s",
                "&7Countdown: &f" + (getConfig().getInt("countdown-seconds", 0) == 0
                        ? "Instant" : getConfig().getInt("countdown-seconds", 0) + "s"),
                "",
                "&d▶ Edit global settings"));

        inventory.setItem(14, guiItem(Material.WRITABLE_BOOK, "&b&lWORLD PROFILES",
                "&8&m--------------------",
                "&7Configure radius, height, timing and",
                "&7search limits separately for each world.",
                "",
                "&b▶ Select a world"));

        inventory.setItem(16, guiItem(Material.CLOCK, "&e&lRELOAD CONFIG",
                "&8&m--------------------",
                "&7Reload config.yml and validate its values.",
                "",
                "&e▶ Reload configuration"));

        inventory.setItem(18, guiItem(Material.PAINTING, "&d&lPLAYER MENU APPEARANCE",
                "&8&m--------------------",
                "&7Change the /rtp menu title, size,",
                "&7filler, close-button position and icons.",
                "",
                "&d▶ Customize player menu"));

        inventory.setItem(20, guiItem(Material.WRITABLE_BOOK, "&b&lMESSAGE EDITOR",
                "&8&m--------------------",
                "&7Customize player-facing messages in-game.",
                "&7Edit prefixes, errors, cooldowns and results.",
                "",
                "&b▶ Open message editor"));

        inventory.setItem(22, guiItem(Material.BARRIER, "&c&lCLOSE",
                "&7Close the administration menu."));

        player.openInventory(inventory);
    }

 
    private static final String GUI_APPEARANCE_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Menu Appearance";

    private void openAppearanceGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, GUI_APPEARANCE_TITLE);
        fillGui(inventory);
        List<String> titles = List.of("&8&lRANDOM TELEPORT", "&5&lRTP SELECTOR", "&1&lDESTINATION SELECTOR", "&0&lWHERE TO?");
        int titleIndex = Math.max(0, titles.indexOf(getConfig().getString("menu.title", "&8&lRANDOM TELEPORT")));
        int size = getConfig().getInt("menu.size", 27);
        boolean filler = getConfig().getBoolean("menu.filler", true);
        int closeSlot = getConfig().getInt("menu.close-slot", size == 27 ? 22 : 49);
        String iconStyle = getConfig().getString("menu.icon-style", "DEFAULT").toUpperCase(java.util.Locale.ROOT);
        inventory.setItem(4, guiItem(Material.PAINTING, "&d&lPLAYER MENU APPEARANCE",
                "&7These options change the menu players see with /rtp.",
                "&7Changes save automatically."));
        inventory.setItem(10, guiItem(Material.NAME_TAG, "&f&lCUSTOM MENU TITLE",
                "&7Current: &f" + getConfig().getString("menu.title", "&8&lRANDOM TELEPORT"),
                "&7Type any title in chat. Color codes with &f& &7work.",
                "", "&d▶ Click, then type title"));
        inventory.setItem(12, guiItem(size == 27 ? Material.CHEST : Material.TRAPPED_CHEST,
                "&f&lMENU SIZE: &d" + size + " SLOTS",
                "&7Switch between compact 27-slot and",
                "&7large 54-slot layouts.",
                "", "&d▶ Click to toggle"));
        inventory.setItem(14, guiItem(filler ? Material.LIME_DYE : Material.GRAY_DYE,
                "&f&lBACKGROUND FILLER: " + (filler ? "&aON" : "&cOFF"),
                "&7Toggle the decorative background panes.",
                "", "&d▶ Click to toggle"));
        inventory.setItem(16, guiItem(Material.ITEM_FRAME, "&f&lWORLD ICON STYLE",
                "&7Current: &f" + iconStyle,
                "&7Cycle the icons used for destinations.",
                "", "&d▶ Click to cycle icons"));
        inventory.setItem(20, guiItem(Material.ITEM_FRAME, "&b&lPER-WORLD ICONS",
                "&7Choose a separate icon for each loaded world.",
                "", "&b▶ Edit world icons"));
        inventory.setItem(24, guiItem(Material.CHEST, "&b&lWORLD BUTTON POSITIONS",
                "&7Choose the exact slot for each world's button.",
                "", "&b▶ Arrange world buttons"));
        inventory.setItem(22, guiItem(Material.BARRIER, "&c&lCLOSE BUTTON POSITION",
                "&7Current slot: &f" + closeSlot,
                "&7Cycles through safe bottom-row positions.",
                "", "&d▶ Click to move"));
        inventory.setItem(18, guiItem(Material.ARROW, "&e&lBACK TO CONTROL CENTER",
                "&7Return to RTP admin menu."));
        player.openInventory(inventory);
    }


    private static final String GUI_WORLD_ICONS_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP World Icons | Page ";
    private static final String GUI_WORLD_SLOTS_TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "RTP Button Slots | Page ";
    private static final String GUI_SLOT_PICKER_PREFIX = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Choose Slot | ";

    private Material defaultWorldIcon(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    private List<Material> worldIconChoices() {
        return List.of(Material.GRASS_BLOCK, Material.COMPASS, Material.LIME_CONCRETE,
                Material.NETHERRACK, Material.NETHER_BRICK, Material.RED_CONCRETE,
                Material.END_STONE, Material.ENDER_EYE, Material.PURPLE_CONCRETE,
                Material.DIAMOND, Material.EMERALD, Material.ENDER_PEARL, Material.MAP,
                Material.CHEST, Material.NETHER_STAR);
    }

    private int defaultSlotForWorld(World world) {
        return switch (world.getName().toLowerCase(java.util.Locale.ROOT)) {
            case "world" -> 11;
            case "world_nether" -> 13;
            case "world_the_end" -> 15;
            default -> -1;
        };
    }

    private void openWorldIconEditor(Player player, int page) {
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_WORLD_ICONS_TITLE + (page + 1));
        fillGui(inventory);
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int maxPage = Math.max(0, (worlds.size() - 1) / 44);
        page = Math.max(0, Math.min(page, maxPage));
        inventory.setItem(4, guiItem(Material.ITEM_FRAME, "&b&lPER-WORLD ICONS", "&7Click a world to cycle its icon."));
        for (int i = 0; i < 45; i++) {
            if (i == 4) continue;
            int index = page * 44 + (i > 4 ? i - 1 : i);
            if (index >= worlds.size()) break;
            World world = worlds.get(index);
            Material icon = Material.matchMaterial(getConfig().getString("menu.world-icons." + world.getName(), defaultWorldIcon(world).name()));
            if (icon == null || !icon.isItem()) icon = defaultWorldIcon(world);
            inventory.setItem(i, guiItem(icon, "&f&l" + world.getName(), "&7Current icon: &f" + icon.name(), "", "&b▶ Click to cycle"));
        }
        inventory.setItem(45, guiItem(Material.ARROW, "&e&lPREVIOUS PAGE"));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&6&lBACK"));
        inventory.setItem(53, guiItem(Material.ARROW, "&e&lNEXT PAGE"));
        player.openInventory(inventory);
    }

    private void openWorldSlotEditor(Player player, int page) {
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_WORLD_SLOTS_TITLE + (page + 1));
        fillGui(inventory);
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int maxPage = Math.max(0, (worlds.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        inventory.setItem(4, guiItem(Material.CHEST, "&b&lWORLD BUTTON POSITIONS", "&7Click a world to choose its player-menu slot."));
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= worlds.size()) break;
            World world = worlds.get(index);
            int current = getConfig().getInt("menu.world-slots." + world.getName(), defaultSlotForWorld(world));
            inventory.setItem(i, guiItem(Material.PAPER, "&f&l" + world.getName(),
                    "&7Current slot: &f" + (current < 0 ? "Automatic" : current), "", "&b▶ Click to arrange"));
        }
        inventory.setItem(45, guiItem(Material.ARROW, "&e&lPREVIOUS PAGE"));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&6&lBACK"));
        inventory.setItem(53, guiItem(Material.ARROW, "&e&lNEXT PAGE"));
        player.openInventory(inventory);
    }

    private void openSlotPicker(Player player, World world) {
        int size = getConfig().getInt("menu.size", 27) == 54 ? 54 : 27;
        Inventory inventory = Bukkit.createInventory(null, size, GUI_SLOT_PICKER_PREFIX + world.getName());
        fillGui(inventory);
        int closeSlot = getConfig().getInt("menu.close-slot", size == 27 ? 22 : 49);
        int previous = size == 27 ? PLAYER_MENU_PREVIOUS_SLOT_27 : PLAYER_MENU_PREVIOUS_SLOT_54;
        int next = size == 27 ? PLAYER_MENU_NEXT_SLOT_27 : PLAYER_MENU_NEXT_SLOT_54;
        for (int i = 0; i < size; i++) {
            if (i == 4 || i == closeSlot || i == previous || i == next) continue;
            inventory.setItem(i, guiItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&fSlot " + i, "&7Place " + world.getName() + " here."));
        }
        inventory.setItem(4, guiItem(Material.COMPASS, "&b&lCHOOSE SLOT", "&7Reserved navigation slots cannot be selected."));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onCustomTitleChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (pendingMessageEdits.containsKey(id)) {
            event.setCancelled(true);
            String typedMessage = event.getMessage().trim();
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = Bukkit.getPlayer(id);
                String key = pendingMessageEdits.remove(id);
                Integer page = messageEditorPages.remove(id);
                if (player == null || key == null) return;
                if (typedMessage.equalsIgnoreCase("cancel")) {
                    player.sendMessage(color("&eMessage edit cancelled."));
                    openMessageEditor(player, page == null ? 0 : page);
                    return;
                }
                if (typedMessage.isEmpty() || typedMessage.length() > 256) {
                    player.sendMessage(color("&cMessage must contain 1–256 characters. Type it again or cancel."));
                    pendingMessageEdits.put(id, key);
                    messageEditorPages.put(id, page == null ? 0 : page);
                    return;
                }
                getConfig().set("messages." + key, typedMessage);
                saveConfig();
                player.sendMessage(color("&aSaved messages." + key + "&7."));
                openMessageEditor(player, page == null ? 0 : page);
            });
            return;
        }
        if (!pendingTitleEdits.contains(id)) return;
        event.setCancelled(true);
        String typed = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !pendingTitleEdits.remove(id)) return;
            if (typed.equalsIgnoreCase("cancel")) {
                player.sendMessage(color("&dRTP &8» &7Title change cancelled."));
                openAppearanceGui(player);
                return;
            }
            if (typed.isBlank() || typed.length() > 64) {
                player.sendMessage(color("&cTitle must contain 1–64 characters. Type it again or cancel."));
                pendingTitleEdits.add(id);
                return;
            }
            getConfig().set("menu.title", typed);
            saveConfig();
            player.sendMessage(color("&aRTP menu title saved."));
            openAppearanceGui(player);
        });
    }

    private void handleAppearanceClick(Player player, int slot) {
        List<String> titles = List.of("&8&lRANDOM TELEPORT", "&5&lRTP SELECTOR", "&1&lDESTINATION SELECTOR", "&0&lWHERE TO?");
        int size = getConfig().getInt("menu.size", 27);
        switch (slot) {
            case 10 -> {
                pendingTitleEdits.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(color("&dRTP &8» &7Type the new menu title in chat. Use &f& &7for colors, or type cancel."));
                return;
            }
            case 12 -> {
                int nextSize = size == 27 ? 54 : 27;
                getConfig().set("menu.size", nextSize);
                int close = getConfig().getInt("menu.close-slot", size == 27 ? 22 : 49);
                if (close >= nextSize) getConfig().set("menu.close-slot", nextSize == 27 ? 22 : 49);
            }
            case 14 -> getConfig().set("menu.filler", !getConfig().getBoolean("menu.filler", true));
            case 16 -> {
                List<String> styles = List.of("DEFAULT", "CLASSIC", "MINIMAL");
                String current = getConfig().getString("menu.icon-style", "DEFAULT").toUpperCase(java.util.Locale.ROOT);
                int index = styles.indexOf(current);
                getConfig().set("menu.icon-style", styles.get((index + 1 + styles.size()) % styles.size()));
            }
            case 20 -> { openWorldIconEditor(player, 0); return; }
            case 24 -> { openWorldSlotEditor(player, 0); return; }
            case 22 -> {
                int menuSize = getConfig().getInt("menu.size", 27);
                int[] positions = menuSize == 27 ? new int[]{20, 22, 24} : new int[]{46, 49, 52};
                int current = getConfig().getInt("menu.close-slot", positions[1]);
                int index = 0;
                for (int i = 0; i < positions.length; i++) if (positions[i] == current) index = i;
                getConfig().set("menu.close-slot", positions[(index + 1) % positions.length]);
            }
            case 18 -> { openMainGui(player); return; }
            default -> { return; }
        }
        saveConfig();
        validateConfig();
        openAppearanceGui(player);
    }

    private List<String> editableMessageKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("prefix");
        if (getConfig().isConfigurationSection("messages")) {
            for (String key : getConfig().getConfigurationSection("messages").getKeys(false)) {
                if (!key.equals("prefix")) keys.add(key);
            }
        }
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        keys.remove("prefix");
        keys.add(0, "prefix");
        return keys;
    }

    private void openMessageEditor(Player player, int requestedPage) {
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_MESSAGES_TITLE + (requestedPage + 1));
        fillGui(inventory);
        List<String> keys = editableMessageKeys();
        int maxPage = Math.max(0, (keys.size() - 1) / 44);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        inventory.setItem(4, guiItem(Material.WRITABLE_BOOK, "&b&lMESSAGE EDITOR",
                "&7Click any message to edit it in chat.",
                "&7Color codes using &f& &7are supported.",
                "&7Edits save immediately to config.yml.",
                "&7Configured messages: &f" + keys.size()));
        for (int slot = 0; slot < 45; slot++) {
            if (slot == 4) continue;
            int index = page * 44 + (slot > 4 ? slot - 1 : slot);
            if (index >= keys.size()) break;
            String key = keys.get(index);
            String value = getConfig().getString("messages." + key, "");
            String preview = value.length() > 42 ? value.substring(0, 42) + "..." : value;
            inventory.setItem(slot, guiItem(key.equals("prefix") ? Material.NAME_TAG : Material.PAPER,
                    "&f&l" + key,
                    "&7Current value:",
                    "&8" + preview,
                    "",
                    "&b▶ Click to edit"));
        }
        inventory.setItem(45, guiItem(Material.ARROW, "&e&lPREVIOUS PAGE"));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&6&lBACK TO CONTROL CENTER"));
        inventory.setItem(53, guiItem(Material.ARROW, "&e&lNEXT PAGE"));
        player.openInventory(inventory);
    }

    private void openSettingsGui(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_SETTINGS_TITLE);
        fillGui(inventory);
        inventory.setItem(4, guiItem(Material.NETHER_STAR, "&6&lRTP SETTINGS",
                "&8&m--------------------",
                "&7Fine-tune RTP behavior and safety.",
                "&7Changes save automatically to config.yml.",
                "",
                "&fClick a control below to adjust it."));
        inventory.setItem(1, guiItem(Material.RED_DYE, "&c&lMIN RADIUS -1000", "&7Global minimum RTP radius.", "&7Current: &f" + getConfig().getInt("minimum-radius", 1000)));
        inventory.setItem(2, guiItem(Material.MAP, "&f&lGLOBAL MINIMUM RADIUS", "&7Current: &f" + getConfig().getInt("minimum-radius", 1000), "&7Minimum distance from world spawn."));
        inventory.setItem(3, guiItem(Material.LIME_DYE, "&a&lMIN RADIUS +1000", "&7Increase global minimum radius."));
        inventory.setItem(5, guiItem(Material.RED_DYE, "&c&lMAX RADIUS -5000", "&7Global maximum RTP radius.", "&7Current: &f" + getConfig().getInt("maximum-radius", 50000)));
        inventory.setItem(6, guiItem(Material.MAP, "&f&lGLOBAL MAXIMUM RADIUS", "&7Current: &f" + getConfig().getInt("maximum-radius", 50000), "&7Maximum distance from world spawn."));
        inventory.setItem(7, guiItem(Material.LIME_DYE, "&a&lMAX RADIUS +5000", "&7Increase global maximum radius."));

        inventory.setItem(10, guiItem(Material.RED_DYE, "&c&lDECREASE COUNTDOWN",
                "&7Shorten the pre-teleport countdown.",
                "&c−1 second per click"));
        inventory.setItem(12, guiItem(Material.CLOCK, "&f&lCOUNTDOWN",
                "&8&m--------------------",
                "&7Current value: &f" + (getConfig().getInt("countdown-seconds", 0) == 0 ? "Instant" : getConfig().getInt("countdown-seconds", 0) + " seconds"),
                "&7Allowed range: &f0–60 seconds",
                "&8Zero means teleport immediately."));
        inventory.setItem(14, guiItem(Material.LIME_DYE, "&a&lINCREASE COUNTDOWN",
                "&7Lengthen the pre-teleport countdown.",
                "&a+1 second per click"));

        inventory.setItem(19, guiItem(Material.RED_DYE, "&c&lDECREASE COOLDOWN",
                "&7Reduce the wait between RTP uses.",
                "&c−5 seconds per click"));
        inventory.setItem(21, guiItem(Material.CLOCK, "&f&lCOOLDOWN",
                "&8&m--------------------",
                "&7Current value: &f" + getConfig().getInt("cooldown-seconds", 30) + " seconds",
                "&7Allowed range: &f0–86400 seconds",
                "&8Set to 0 to disable cooldown."));
        inventory.setItem(23, guiItem(Material.LIME_DYE, "&a&lINCREASE COOLDOWN",
                "&7Increase the wait between RTP uses.",
                "&a+5 seconds per click"));

        inventory.setItem(28, guiItem(Material.RED_DYE, "&c&lFEWER ATTEMPTS",
                "&7Try fewer candidate locations per request.",
                "&c−5 attempts per click"));
        inventory.setItem(30, guiItem(Material.PAPER, "&f&lMAXIMUM ATTEMPTS",
                "&8&m--------------------",
                "&7Current value: &f" + getConfig().getInt("maximum-attempts", 30),
                "&7Allowed range: &f1–200 attempts",
                "&8Maximum candidate locations per request."));
        inventory.setItem(32, guiItem(Material.LIME_DYE, "&a&lMORE ATTEMPTS",
                "&7Try more candidate locations per request.",
                "&a+5 attempts per click"));

        inventory.setItem(37, guiItem(Material.RED_DYE, "&c&lFEWER SEARCHES",
                "&7Lower the number of searches allowed at once.",
                "&c−5 searches per click"));
        inventory.setItem(39, guiItem(Material.BOOK, "&f&lCONCURRENT SEARCHES",
                "&8&m--------------------",
                "&7Current value: &f" + getConfig().getInt("maximum-active-searches", 25),
                "&7Allowed range: &f1–500 searches",
                "&8Limits simultaneous RTP searches."));
        inventory.setItem(41, guiItem(Material.LIME_DYE, "&a&lMORE SEARCHES",
                "&7Allow more searches to run at once.",
                "&a+5 searches per click"));

        inventory.setItem(46, guiItem(Material.RED_DYE, "&c&lSHORTER TIMEOUT",
                "&7Stop stalled searches sooner.",
                "&c−5 seconds per click"));
        inventory.setItem(48, guiItem(Material.CLOCK, "&f&lSEARCH TIMEOUT",
                "&8&m--------------------",
                "&7Current value: &f" + getConfig().getInt("search-timeout-seconds", 20) + " seconds",
                "&7Allowed range: &f5–120 seconds",
                "&8Maximum time allowed for a search."));
        inventory.setItem(50, guiItem(Material.LIME_DYE, "&a&lLONGER TIMEOUT",
                "&7Allow searches more time to finish.",
                "&a+5 seconds per click"));
        inventory.setItem(43, guiItem(getConfig().getBoolean("load-chunks", false) ? Material.LIME_DYE : Material.GRAY_DYE,
                "&f&lChunk Loading: " + (getConfig().getBoolean("load-chunks", false) ? "&aEnabled" : "&cDisabled"),
                "&8&m--------------------",
                "&7Permit asynchronous loading or generation",
                "&7of destination chunks when required.",
                getConfig().getBoolean("load-chunks", false) ? "&aCurrently ON" : "&cCurrently OFF",
                "",
                "&e▶ Click to toggle"));
        inventory.setItem(44, guiItem(getConfig().getBoolean("avoid-water", true) ? Material.LIME_DYE : Material.GRAY_DYE,
                "&f&lAvoid Water: " + (getConfig().getBoolean("avoid-water", true) ? "&aEnabled" : "&cDisabled"),
                "&8&m--------------------",
                "&7Reject water and bubble columns when",
                "&7checking candidate destinations.",
                getConfig().getBoolean("avoid-water", true) ? "&aCurrently ON" : "&cCurrently OFF",
                "",
                "&e▶ Click to toggle"));
        inventory.setItem(52, guiItem(Material.OAK_DOOR, "&6&lBACK TO MENU",
                "&7Return to the RTP Control Center.",
                "",
                "&6▶ Click to go back"));
        player.openInventory(inventory);
    }

    private void adjustSetting(Player player, int slot) {
        String path = null;
        int delta = 0;
        switch (slot) {
            case 1 -> { path = "minimum-radius"; delta = -1000; }
            case 3 -> { path = "minimum-radius"; delta = 1000; }
            case 5 -> { path = "maximum-radius"; delta = -5000; }
            case 7 -> { path = "maximum-radius"; delta = 5000; }
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
        if (path.equals("minimum-radius")) {
            int maxRadius = getConfig().getInt("maximum-radius", 50000);
            getConfig().set(path, Math.max(0, Math.min(maxRadius - 1, value + delta)));
            saveConfig();
            openSettingsGui(player);
            return;
        }
        if (path.equals("maximum-radius")) {
            int minRadius = getConfig().getInt("minimum-radius", 1000);
            getConfig().set(path, Math.max(minRadius + 1, Math.min(30_000_000, value + delta)));
            saveConfig();
            openSettingsGui(player);
            return;
        }
        int min = switch (path) {
            case "countdown-seconds" -> 0;
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
            boolean enabled = !disabled.contains(world.getName()) && isWorldRtpEnabled(world);
            Material icon = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
            inventory.setItem(i - start, guiItem(icon,
                    (enabled ? "&a&lENABLED &8» &f" : "&c&lDISABLED &8» &f") + world.getName(),
                    "&8&m--------------------",
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
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&6&lBACK TO CONTROL CENTER",
                "&7Return to the RTP main menu.",
                "",
                "&6▶ Click to go back"));
        inventory.setItem(53, guiItem(Material.ARROW, "&e&lNEXT PAGE",
                "&7Browse the next page of worlds.",
                page < maxPage ? "&e▶ Click to continue" : "&8You are on the last page"));
        inventory.setItem(48, guiItem(Material.BOOK, "&f&lPAGE INFORMATION",
                "&8&m--------------------",
                "&7Page: &f" + (page + 1) + " &8/ &f" + (maxPage + 1),
                "&7Loaded worlds: &f" + worlds.size(),
                "",
                "&8Click a world to toggle its RTP status."));
        player.openInventory(inventory);
    }

    private void openWorldSettingsSelector(Player player) {
        openWorldSettingsSelector(player, 0);
    }

    private void openWorldSettingsSelector(Player player, int requestedPage) {
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        int pageSize = 44; // Slot 4 is reserved for the title card.
        int maxPage = Math.max(0, (worlds.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inventory = Bukkit.createInventory(null, 54, GUI_WORLD_SETTINGS_SELECT_TITLE + (page + 1));
        fillGui(inventory);
        inventory.setItem(4, guiItem(Material.COMPARATOR, "&6&lPER-WORLD RTP SETTINGS",
                "&8&m--------------------",
                "&7Select a loaded world to edit its RTP settings.",
                "&7Changes save automatically to config.yml.",
                "",
                "&7Page: &f" + (page + 1) + " &8/ &f" + (maxPage + 1)));
        int start = page * pageSize;
        int end = Math.min(start + pageSize, worlds.size());
        for (int i = start; i < end; i++) {
            World world = worlds.get(i);
            int slot = i - start;
            if (slot >= 4) slot++;
            boolean enabled = isWorldRtpEnabled(world)
                    && !getConfig().getStringList("disabled-worlds").contains(world.getName());
            inventory.setItem(slot, guiItem(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    (enabled ? "&a&lENABLED &8» &f" : "&c&lDISABLED &8» &f") + world.getName(),
                    "&8&m--------------------",
                    "&7Environment: &f" + world.getEnvironment().name(),
                    "&7Radius: &f" + worldInt(world, "minimum-radius", getConfig().getInt("minimum-radius", 100))
                            + "–" + worldInt(world, "maximum-radius", getConfig().getInt("maximum-radius", 5000)),
                    "&7Countdown: &f" + worldInt(world, "countdown-seconds", getConfig().getInt("countdown-seconds", 5)) + "s",
                    "",
                    "&6▶ Click to edit this world"));
        }
        inventory.setItem(45, guiItem(Material.ARROW, "&e&lPREVIOUS PAGE",
                page > 0 ? "&7Go to page " + page : "&8You are on the first page"));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&6&lBACK TO CONTROL CENTER", "&7Return to the main admin menu."));
        inventory.setItem(53, guiItem(Material.ARROW, "&e&lNEXT PAGE",
                page < maxPage ? "&7Go to page " + (page + 2) : "&8You are on the last page"));
        player.openInventory(inventory);
    }
    private void openWorldSettingsGui(Player player, World world) {
        String title = GUI_WORLD_SETTINGS_PREFIX + world.getName();
        Inventory inventory = Bukkit.createInventory(null, 54, title);
        fillGui(inventory);
        int minRadius = worldInt(world, "minimum-radius", getConfig().getInt("minimum-radius", 100));
        int maxRadius = worldInt(world, "maximum-radius", getConfig().getInt("maximum-radius", 5000));
        int minY = worldInt(world, "minimum-y", world.getMinHeight());
        int maxY = worldInt(world, "maximum-y", world.getMaxHeight() - 2);
        int countdown = worldInt(world, "countdown-seconds", getConfig().getInt("countdown-seconds", 5));
        long cooldown = worldLong(world, "cooldown-seconds", getConfig().getLong("cooldown-seconds", 30));
        int attempts = worldInt(world, "maximum-attempts", getConfig().getInt("maximum-attempts", 30));
        boolean enabled = isWorldRtpEnabled(world)
                && !getConfig().getStringList("disabled-worlds").contains(world.getName());
        inventory.setItem(4, guiItem(Material.COMPARATOR, "&6&lWORLD SETTINGS",
                "&8&m--------------------",
                "&7World: &f" + world.getName(),
                "&7Environment: &f" + world.getEnvironment().name(),
                "&7Values inherited from global config are shown as effective values.",
                "",
                "&aChanges save automatically."));
        inventory.setItem(10, guiItem(Material.RED_DYE, "&c&lMIN RADIUS -100", "&7Decrease minimum RTP radius."));
        inventory.setItem(11, guiItem(Material.MAP, "&f&lMINIMUM RADIUS", "&7Current: &f" + minRadius, "&7Must stay below maximum radius."));
        inventory.setItem(12, guiItem(Material.LIME_DYE, "&a&lMIN RADIUS +100", "&7Increase minimum RTP radius."));
        inventory.setItem(14, guiItem(Material.RED_DYE, "&c&lMAX RADIUS -500", "&7Decrease maximum RTP radius."));
        inventory.setItem(15, guiItem(Material.MAP, "&f&lMAXIMUM RADIUS", "&7Current: &f" + maxRadius, "&7Maximum supported: 30,000,000."));
        inventory.setItem(16, guiItem(Material.LIME_DYE, "&a&lMAX RADIUS +500", "&7Increase maximum RTP radius."));
        inventory.setItem(19, guiItem(Material.RED_DYE, "&c&lCOUNTDOWN -1", "&7Current: &f" + countdown + "s"));
        inventory.setItem(20, guiItem(Material.CLOCK, "&f&lCOUNTDOWN", "&7Current: &f" + countdown + "s", "&7Range: 1–60 seconds."));
        inventory.setItem(21, guiItem(Material.LIME_DYE, "&a&lCOUNTDOWN +1", "&7Increase countdown."));
        inventory.setItem(23, guiItem(Material.RED_DYE, "&c&lCOOLDOWN -5", "&7Current: &f" + cooldown + "s"));
        inventory.setItem(24, guiItem(Material.CLOCK, "&f&lCOOLDOWN", "&7Current: &f" + cooldown + "s", "&7Range: 0–86400 seconds."));
        inventory.setItem(25, guiItem(Material.LIME_DYE, "&a&lCOOLDOWN +5", "&7Increase cooldown."));
        inventory.setItem(28, guiItem(Material.RED_DYE, "&c&lMIN Y -10", "&7Current: &f" + minY));
        inventory.setItem(29, guiItem(Material.IRON_PICKAXE, "&f&lMINIMUM Y", "&7Current: &f" + minY, "&7World floor: " + world.getMinHeight()));
        inventory.setItem(30, guiItem(Material.LIME_DYE, "&a&lMIN Y +10", "&7Increase minimum accepted height."));
        inventory.setItem(32, guiItem(Material.RED_DYE, "&c&lMAX Y -10", "&7Current: &f" + maxY));
        inventory.setItem(33, guiItem(Material.IRON_PICKAXE, "&f&lMAXIMUM Y", "&7Current: &f" + maxY, "&7World ceiling: " + (world.getMaxHeight() - 2)));
        inventory.setItem(34, guiItem(Material.LIME_DYE, "&a&lMAX Y +10", "&7Increase maximum accepted height."));
        inventory.setItem(37, guiItem(Material.RED_DYE, "&c&lATTEMPTS -5", "&7Current: &f" + attempts));
        inventory.setItem(38, guiItem(Material.PAPER, "&f&lMAXIMUM ATTEMPTS", "&7Current: &f" + attempts, "&7Range: 1–200."));
        inventory.setItem(39, guiItem(Material.LIME_DYE, "&a&lATTEMPTS +5", "&7Increase search attempts."));
        int timeout = worldInt(world, "search-timeout-seconds", getConfig().getInt("search-timeout-seconds", 20));
        inventory.setItem(42, guiItem(Material.RED_DYE, "&c&lSEARCH TIMEOUT -5", "&7Current: &f" + timeout + "s"));
        inventory.setItem(43, guiItem(Material.CLOCK, "&f&lSEARCH TIMEOUT", "&7Current: &f" + timeout + "s", "&7Range: 5–120 seconds."));
        inventory.setItem(44, guiItem(Material.LIME_DYE, "&a&lSEARCH TIMEOUT +5", "&7Increase search timeout."));
        inventory.setItem(41, guiItem(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                enabled ? "&a&lWORLD RTP: ENABLED" : "&c&lWORLD RTP: DISABLED",
                "&7Toggle whether this world is available in /rtp.",
                "&e▶ Click to toggle"));
        inventory.setItem(47, guiItem(Material.FILLED_MAP, "&b&lRTP AREA BOUNDS",
                "&7Set rectangular X/Z boundaries for this world.",
                "&7Use your current position for each coordinate.",
                "&e▶ Click to edit area bounds"));
        inventory.setItem(45, guiItem(Material.ARROW, "&e&lBACK TO WORLD LIST", "&7Select another world."));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&6&lBACK TO CONTROL CENTER", "&7Return to the main admin menu."));
        player.openInventory(inventory);
    }


    private void openWorldAreaGui(Player player, World world) {
        String title = GUI_WORLD_AREA_PREFIX + world.getName();
        Inventory inventory = Bukkit.createInventory(null, 54, title);
        fillGui(inventory);
        String base = "world-areas." + world.getName() + ".";
        inventory.setItem(4, guiItem(Material.FILLED_MAP, "&b&lRECTANGULAR RTP AREA",
                "&7World: &f" + world.getName(),
                "&7Each coordinate is set to your current block position.",
                "&7All four bounds must be set and min must not exceed max.",
                "",
                "&eMove to a corner, then click the matching control."));
        inventory.setItem(10, guiItem(Material.RED_DYE, "&c&lSET MIN X",
                "&7Current: &f" + (getConfig().contains(base + "min-x") ? getConfig().getInt(base + "min-x") : "Not set"),
                "&e▶ Set min X to your current X: &f" + player.getLocation().getBlockX()));
        inventory.setItem(12, guiItem(Material.LIME_DYE, "&a&lSET MAX X",
                "&7Current: &f" + (getConfig().contains(base + "max-x") ? getConfig().getInt(base + "max-x") : "Not set"),
                "&e▶ Set max X to your current X: &f" + player.getLocation().getBlockX()));
        inventory.setItem(14, guiItem(Material.RED_DYE, "&c&lSET MIN Z",
                "&7Current: &f" + (getConfig().contains(base + "min-z") ? getConfig().getInt(base + "min-z") : "Not set"),
                "&e▶ Set min Z to your current Z: &f" + player.getLocation().getBlockZ()));
        inventory.setItem(16, guiItem(Material.LIME_DYE, "&a&lSET MAX Z",
                "&7Current: &f" + (getConfig().contains(base + "max-z") ? getConfig().getInt(base + "max-z") : "Not set"),
                "&e▶ Set max Z to your current Z: &f" + player.getLocation().getBlockZ()));
        inventory.setItem(22, guiItem(Material.PAPER, "&f&lCURRENT BOUNDS",
                "&7Min X: &f" + (getConfig().contains(base + "min-x") ? getConfig().getInt(base + "min-x") : "Not set"),
                "&7Max X: &f" + (getConfig().contains(base + "max-x") ? getConfig().getInt(base + "max-x") : "Not set"),
                "&7Min Z: &f" + (getConfig().contains(base + "min-z") ? getConfig().getInt(base + "min-z") : "Not set"),
                "&7Max Z: &f" + (getConfig().contains(base + "max-z") ? getConfig().getInt(base + "max-z") : "Not set")));
        inventory.setItem(31, guiItem(Material.BARRIER, "&c&lCLEAR CUSTOM AREA",
                "&7Remove the rectangle and use global radius settings instead.",
                "&e▶ Click to clear"));
        inventory.setItem(45, guiItem(Material.ARROW, "&e&lBACK TO WORLD SETTINGS", "&7Return to this world's settings."));
        inventory.setItem(49, guiItem(Material.OAK_DOOR, "&6&lBACK TO CONTROL CENTER", "&7Return to the admin menu."));
        player.openInventory(inventory);
    }

    private void handleWorldAreaClick(Player player, World world, int slot) {
        String base = "world-areas." + world.getName() + ".";
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        switch (slot) {
            case 10 -> {
                getConfig().set(base + "min-x", x);
                if (getConfig().contains(base + "max-x") && x > getConfig().getInt(base + "max-x")) {
                    getConfig().set(base + "max-x", x);
                }
            }
            case 12 -> {
                getConfig().set(base + "max-x", x);
                if (getConfig().contains(base + "min-x") && x < getConfig().getInt(base + "min-x")) {
                    getConfig().set(base + "min-x", x);
                }
            }
            case 14 -> {
                getConfig().set(base + "min-z", z);
                if (getConfig().contains(base + "max-z") && z > getConfig().getInt(base + "max-z")) {
                    getConfig().set(base + "max-z", z);
                }
            }
            case 16 -> {
                getConfig().set(base + "max-z", z);
                if (getConfig().contains(base + "min-z") && z < getConfig().getInt(base + "min-z")) {
                    getConfig().set(base + "min-z", z);
                }
            }
            case 31 -> {
                getConfig().set("world-areas." + world.getName(), null);
            }
            case 45 -> { openWorldSettingsGui(player, world); return; }
            case 49 -> { openMainGui(player); return; }
            default -> { return; }
        }
        saveConfig();
        openWorldAreaGui(player, world);
    }

    private void adjustWorldSetting(Player player, World world, int slot) {
        String base = "world-settings." + world.getName() + ".";
        int minRadius = worldInt(world, "minimum-radius", getConfig().getInt("minimum-radius", 100));
        int maxRadius = worldInt(world, "maximum-radius", getConfig().getInt("maximum-radius", 5000));
        int minY = worldInt(world, "minimum-y", world.getMinHeight());
        int maxY = worldInt(world, "maximum-y", world.getMaxHeight() - 2);
        switch (slot) {
            case 10 -> getConfig().set(base + "minimum-radius", Math.max(0, Math.min(minRadius - 100, maxRadius - 1)));
            case 12 -> getConfig().set(base + "minimum-radius", Math.max(0, Math.min(maxRadius - 1, minRadius + 100)));
            case 14 -> getConfig().set(base + "maximum-radius", Math.max(minRadius + 1, maxRadius - 500));
            case 16 -> getConfig().set(base + "maximum-radius", Math.min(30_000_000, maxRadius + 500));
            case 19 -> getConfig().set(base + "countdown-seconds", Math.max(0, worldInt(world, "countdown-seconds", getConfig().getInt("countdown-seconds", 0)) - 1));
            case 21 -> getConfig().set(base + "countdown-seconds", Math.min(60, worldInt(world, "countdown-seconds", getConfig().getInt("countdown-seconds", 5)) + 1));
            case 23 -> getConfig().set(base + "cooldown-seconds", Math.max(0, worldLong(world, "cooldown-seconds", getConfig().getLong("cooldown-seconds", 30)) - 5));
            case 25 -> getConfig().set(base + "cooldown-seconds", Math.min(86400, worldLong(world, "cooldown-seconds", getConfig().getLong("cooldown-seconds", 30)) + 5));
            case 28 -> getConfig().set(base + "minimum-y", Math.max(world.getMinHeight(), minY - 10));
            case 30 -> getConfig().set(base + "minimum-y", Math.min(maxY, minY + 10));
            case 32 -> getConfig().set(base + "maximum-y", Math.max(minY, maxY - 10));
            case 34 -> getConfig().set(base + "maximum-y", Math.min(world.getMaxHeight() - 2, maxY + 10));
            case 37 -> getConfig().set(base + "maximum-attempts", Math.max(1, worldInt(world, "maximum-attempts", getConfig().getInt("maximum-attempts", 30)) - 5));
            case 39 -> getConfig().set(base + "maximum-attempts", Math.min(200, worldInt(world, "maximum-attempts", getConfig().getInt("maximum-attempts", 30)) + 5));
            case 41 -> {
                boolean currentlyEnabled = isWorldRtpEnabled(world)
                        && !getConfig().getStringList("disabled-worlds").contains(world.getName());
                getConfig().set(base + "enabled", !currentlyEnabled);
                List<String> disabledWorlds = new ArrayList<>(getConfig().getStringList("disabled-worlds"));
                if (currentlyEnabled) {
                    if (!disabledWorlds.contains(world.getName())) disabledWorlds.add(world.getName());
                } else {
                    disabledWorlds.remove(world.getName());
                }
                getConfig().set("disabled-worlds", disabledWorlds);
            }
            case 42 -> getConfig().set(base + "search-timeout-seconds",
                    Math.max(5, worldInt(world, "search-timeout-seconds", getConfig().getInt("search-timeout-seconds", 20)) - 5));
            case 44 -> getConfig().set(base + "search-timeout-seconds",
                    Math.min(120, worldInt(world, "search-timeout-seconds", getConfig().getInt("search-timeout-seconds", 20)) + 5));
            default -> { return; }
        }
        saveConfig();
        openWorldSettingsGui(player, world);
    }

    private void fillGui(Inventory inventory) {
        ItemStack darkFiller = guiItem(Material.BLACK_STAINED_GLASS_PANE, "&0 ");
        ItemStack accentFiller = guiItem(Material.GRAY_STAINED_GLASS_PANE, "&8 ");
        ItemStack highlightFiller = guiItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&b ");
        int size = inventory.getSize();
        int columns = 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / columns;
            int column = slot % columns;
            boolean border = row == 0 || column == 0 || column == columns - 1 || slot >= size - columns;
            ItemStack pane = !border ? darkFiller
                    : ((row + column) % 2 == 0 ? accentFiller : darkFiller);
            inventory.setItem(slot, pane);
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
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                    org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                    org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onPlayerRtpMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        String pagePrefix = playerMenuTitle() + ChatColor.DARK_GRAY + " | Page ";
        boolean firstPage = title.equals(playerMenuTitle());
        if (!firstPage && !title.startsWith(pagePrefix)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        int menuSize = event.getView().getTopInventory().getSize();
        if (slot < 0 || slot >= menuSize) return;

        int page = 0;
        if (!firstPage) {
            try {
                page = Integer.parseInt(title.substring(pagePrefix.length())) - 1;
            } catch (NumberFormatException exception) {
                player.closeInventory();
                return;
            }
        }
        int closeSlot = Math.max(0, Math.min(menuSize - 1, getConfig().getInt("menu.close-slot", 22)));
        if (slot == closeSlot) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.0f);
            return;
        }
        int previousSlot = menuSize <= 27 ? PLAYER_MENU_PREVIOUS_SLOT_27 : PLAYER_MENU_PREVIOUS_SLOT_54;
        int nextSlot = menuSize <= 27 ? PLAYER_MENU_NEXT_SLOT_27 : PLAYER_MENU_NEXT_SLOT_54;
        List<World> worlds = getSelectableWorlds();
        List<Integer> worldSlots = playerWorldMenuSlots(menuSize, page);
        int pageSize = worldSlots.size();
        int maxPage = pageSize == 0 ? 0 : Math.max(0, (worlds.size() - 1) / pageSize);
        if (slot == previousSlot && page > 0) {
            openPlayerGui(player, page - 1);
            return;
        }
        if (slot == nextSlot && page < maxPage) {
            openPlayerGui(player, page + 1);
            return;
        }
        int selectedIndex = worldSlots.indexOf(slot);
        int worldIndex = page * pageSize + selectedIndex;
        if (selectedIndex >= 0 && worldIndex >= 0 && worldIndex < worlds.size()) {
            requestTeleport(player, worlds.get(worldIndex));
        }
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
            } else if (slot == 12) {
                openSettingsGui(player);
            } else if (slot == 14) {
                openWorldSettingsSelector(player);
            } else if (slot == 16) {
                reloadConfig();
                validateConfig();
                player.sendMessage(message("reloaded"));
                openMainGui(player);
            } else if (slot == 18) {
                openAppearanceGui(player);
            } else if (slot == 20) {
                openMessageEditor(player, 0);
            } else if (slot == 22) {
                player.closeInventory();
            }
            return;
        }

        if (title.startsWith(GUI_MESSAGES_TITLE)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) { player.closeInventory(); return; }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 54) return;
            int page;
            try { page = Integer.parseInt(title.substring(GUI_MESSAGES_TITLE.length())) - 1; }
            catch (NumberFormatException exception) { player.closeInventory(); return; }
            List<String> keys = editableMessageKeys();
            int maxPage = Math.max(0, (keys.size() - 1) / 44);
            if (slot == 49) { openMainGui(player); return; }
            if (slot == 45) { if (page > 0) openMessageEditor(player, page - 1); return; }
            if (slot == 53) { if (page < maxPage) openMessageEditor(player, page + 1); return; }
            if (slot >= 45 || slot == 4) return;
            int index = page * 44 + (slot > 4 ? slot - 1 : slot);
            if (index >= keys.size()) return;
            String key = keys.get(index);
            pendingMessageEdits.put(player.getUniqueId(), key);
            messageEditorPages.put(player.getUniqueId(), page);
            player.closeInventory();
            player.sendMessage(color("&dRTP &8» &7Type the new value for &fmessages." + key
                    + "&7 in chat. Use &f& &7for colors, or type &fcancel&7 to abort."));
            return;
        }

        if (title.startsWith(GUI_WORLD_ICONS_TITLE)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) { player.closeInventory(); return; }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 54) return;
            int page;
            try { page = Integer.parseInt(title.substring(GUI_WORLD_ICONS_TITLE.length())) - 1; }
            catch (NumberFormatException exception) { player.closeInventory(); return; }
            if (slot == 49) { openAppearanceGui(player); return; }
            List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
            int maxPage = Math.max(0, (worlds.size() - 1) / 45);
            if (slot == 45) { if (page > 0) openWorldIconEditor(player, page - 1); return; }
            if (slot == 53) { if (page < maxPage) openWorldIconEditor(player, page + 1); return; }
            if (slot >= 45) return;
            int index = page * 45 + slot;
            if (index >= worlds.size()) return;
            World world = worlds.get(index);
            String path = "menu.world-icons." + world.getName();
            Material current = Material.matchMaterial(getConfig().getString(path, defaultWorldIcon(world).name()));
            List<Material> choices = worldIconChoices();
            int currentIndex = choices.indexOf(current);
            getConfig().set(path, choices.get((currentIndex + 1 + choices.size()) % choices.size()).name());
            saveConfig();
            openWorldIconEditor(player, page);
            return;
        }
        if (title.startsWith(GUI_WORLD_SLOTS_TITLE)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) { player.closeInventory(); return; }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 54) return;
            int page;
            try { page = Integer.parseInt(title.substring(GUI_WORLD_SLOTS_TITLE.length())) - 1; }
            catch (NumberFormatException exception) { player.closeInventory(); return; }
            if (slot == 49) { openAppearanceGui(player); return; }
            List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
            int maxPage = Math.max(0, (worlds.size() - 1) / 45);
            if (slot == 45) { if (page > 0) openWorldSlotEditor(player, page - 1); return; }
            if (slot == 53) { if (page < maxPage) openWorldSlotEditor(player, page + 1); return; }
            if (slot >= 45) return;
            int index = page * 45 + slot;
            if (index >= worlds.size()) return;
            openSlotPicker(player, worlds.get(index));
            return;
        }
        if (title.startsWith(GUI_SLOT_PICKER_PREFIX)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) { player.closeInventory(); return; }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            World world = Bukkit.getWorld(title.substring(GUI_SLOT_PICKER_PREFIX.length()));
            if (world == null) { player.closeInventory(); return; }
            int size = event.getView().getTopInventory().getSize();
            int closeSlot = getConfig().getInt("menu.close-slot", size == 27 ? 22 : 49);
            int previous = size == 27 ? PLAYER_MENU_PREVIOUS_SLOT_27 : PLAYER_MENU_PREVIOUS_SLOT_54;
            int next = size == 27 ? PLAYER_MENU_NEXT_SLOT_27 : PLAYER_MENU_NEXT_SLOT_54;
            if (slot == 4 || slot == closeSlot || slot == previous || slot == next) return;
            boolean occupied = Bukkit.getWorlds().stream().filter(other -> !other.equals(world))
                    .anyMatch(other -> getConfig().getInt("menu.world-slots." + other.getName(), defaultSlotForWorld(other)) == slot);
            if (occupied) { player.sendMessage(color("&cThat slot is already assigned to another world.")); return; }
            getConfig().set("menu.world-slots." + world.getName(), slot);
            saveConfig();
            player.sendMessage(color("&aSaved slot &f" + slot + " &afor &f" + world.getName() + "&a."));
            openWorldSlotEditor(player, 0);
            return;
        }
        if (title.equals(GUI_APPEARANCE_TITLE)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) {
                player.closeInventory();
                return;
            }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            handleAppearanceClick(player, slot);
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
        if (title.startsWith(GUI_WORLD_SETTINGS_SELECT_TITLE)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) { player.closeInventory(); return; }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            int page;
            try { page = Integer.parseInt(title.substring(GUI_WORLD_SETTINGS_SELECT_TITLE.length())) - 1; }
            catch (NumberFormatException exception) { player.closeInventory(); return; }
            if (slot == 49) { openMainGui(player); return; }
            List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
            int pageSize = 44;
            int maxPage = Math.max(0, (worlds.size() - 1) / pageSize);
            if (slot == 45) { if (page > 0) openWorldSettingsSelector(player, page - 1); return; }
            if (slot == 53) { if (page < maxPage) openWorldSettingsSelector(player, page + 1); return; }
            if (slot == 4 || slot >= 45) return;
            int localIndex = slot > 4 ? slot - 1 : slot;
            int worldIndex = page * pageSize + localIndex;
            if (worldIndex >= 0 && worldIndex < worlds.size()) openWorldSettingsGui(player, worlds.get(worldIndex));
            return;
        }
        if (title.startsWith(GUI_WORLD_AREA_PREFIX)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) { player.closeInventory(); return; }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            String worldName = title.substring(GUI_WORLD_AREA_PREFIX.length());
            World world = Bukkit.getWorld(worldName);
            if (world == null) { player.closeInventory(); return; }
            handleWorldAreaClick(player, world, slot);
            return;
        }
        if (title.startsWith(GUI_WORLD_SETTINGS_PREFIX)) {
            event.setCancelled(true);
            if (!player.hasPermission("rtp.admin")) { player.closeInventory(); return; }
            String worldName = title.substring(GUI_WORLD_SETTINGS_PREFIX.length());
            World world = Bukkit.getWorld(worldName);
            if (world == null) { player.closeInventory(); return; }
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            if (slot == 45) { openWorldSettingsSelector(player); return; }
            if (slot == 49) { openMainGui(player); return; }
            if (slot == 47) { openWorldAreaGui(player, world); return; }
            adjustWorldSetting(player, world, slot);
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
        boolean currentlyEnabled = !disabled.contains(worldName) && isWorldRtpEnabled(worlds.get(worldIndex));
        if (currentlyEnabled) {
            disabled.add(worldName);
            getConfig().set("world-settings." + worldName + ".enabled", false);
            player.sendMessage(message("world-disabled-admin").replace("%world%", worldName));
        } else {
            disabled.remove(worldName);
            getConfig().set("world-settings." + worldName + ".enabled", true);
            player.sendMessage(message("world-enabled").replace("%world%", worldName));
        }
        getConfig().set("disabled-worlds", disabled);
        saveConfig();
        openWorldGui(player, page);
    }

    private boolean isUnsafe(Material material) {
        if (material == Material.BEDROCK || material == Material.LAVA || material == Material.MAGMA_BLOCK || material == Material.CACTUS
                || material == Material.FIRE || material == Material.SOUL_FIRE
                || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.POWDER_SNOW || material == Material.SWEET_BERRY_BUSH
                || material == Material.WITHER_ROSE || material == Material.VOID_AIR
                || material == Material.COBWEB || material == Material.POINTED_DRIPSTONE) {
            return true;
        }
        if (material.name().endsWith("_LEAVES") || material == Material.AZALEA_LEAVES
                || material == Material.FLOWERING_AZALEA_LEAVES) return true;
        return getConfig().getBoolean("avoid-water", true)
                && (material == Material.WATER || material == Material.BUBBLE_COLUMN);
    }

    private void validateConfig() {
        int configuredMenuSize = getConfig().getInt("menu.size", 27);
        if (configuredMenuSize != 27 && configuredMenuSize != 54) {
            getLogger().warning("menu.size must be 27 or 54; using 27.");
            getConfig().set("menu.size", 27);
        }
        int closeSlot = getConfig().getInt("menu.close-slot", 22);
        if (closeSlot < 0 || closeSlot >= getConfig().getInt("menu.size", 27)) {
            getLogger().warning("menu.close-slot is outside the configured menu; using 22.");
            getConfig().set("menu.close-slot", 22);
        }

        int countdown = getConfig().getInt("countdown-seconds", 0);
        if (countdown < 0 || countdown > 60) {
            getLogger().warning("countdown-seconds must be between 0 and 60; using 0 (instant teleport).");
            getConfig().set("countdown-seconds", 0);
        }

        long cooldown = getConfig().getLong("cooldown-seconds", 30);
        if (cooldown < 0 || cooldown > 86400) {
            getLogger().warning("cooldown-seconds must be between 0 and 86400; using 30.");
            getConfig().set("cooldown-seconds", 30);
        }

        int attempts = getConfig().getInt("maximum-attempts", 200);
        if (attempts < 1 || attempts > 200) {
            getLogger().warning("maximum-attempts must be between 1 and 200; using 200.");
            getConfig().set("maximum-attempts", 200);
        }

        int minRadius = getConfig().getInt("minimum-radius", 1000);
        int maxRadius = getConfig().getInt("maximum-radius", 50000);
        // Migrate the previous bundled defaults while preserving deliberate custom values.
        if (minRadius == 100 && maxRadius == 5000) {
            minRadius = 1000;
            maxRadius = 50000;
            getConfig().set("minimum-radius", minRadius);
            getConfig().set("maximum-radius", maxRadius);
        }
        if (minRadius < 0 || minRadius > 30_000_000 || maxRadius <= minRadius || maxRadius > 30_000_000) {
            getLogger().warning("Invalid RTP radius values; restoring minimum-radius=1000 and maximum-radius=50000.");
            getConfig().set("minimum-radius", 1000);
            getConfig().set("maximum-radius", 50000);
        }
        if (getConfig().getInt("countdown-seconds", 5) == 5) getConfig().set("countdown-seconds", 0);
        if (getConfig().getInt("maximum-attempts", 30) == 30) getConfig().set("maximum-attempts", 200);
        if (!getConfig().getBoolean("load-chunks", true)) getConfig().set("load-chunks", true);

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

        var worldSettings = getConfig().getConfigurationSection("world-settings");
        if (worldSettings != null) {
            for (String worldName : worldSettings.getKeys(false)) {
                String base = "world-settings." + worldName + ".";
                int worldMinRadius = getConfig().getInt(base + "minimum-radius",
                        getConfig().getInt("minimum-radius", 100));
                int worldMaxRadius = getConfig().getInt(base + "maximum-radius",
                        getConfig().getInt("maximum-radius", 5000));
                if (worldMinRadius < 0 || worldMinRadius >= 30_000_000) {
                    getLogger().warning("Invalid minimum-radius for world '" + worldName + "'; using global value.");
                    getConfig().set(base + "minimum-radius", getConfig().getInt("minimum-radius", 100));
                    worldMinRadius = getConfig().getInt(base + "minimum-radius");
                }
                if (worldMaxRadius <= worldMinRadius || worldMaxRadius > 30_000_000) {
                    int fallbackMax = Math.min(30_000_000, Math.max(worldMinRadius + 1,
                            getConfig().getInt("maximum-radius", 5000)));
                    getLogger().warning("Invalid maximum-radius for world '" + worldName + "'; using " + fallbackMax + ".");
                    getConfig().set(base + "maximum-radius", fallbackMax);
                }

                int worldCountdown = getConfig().getInt(base + "countdown-seconds",
                        getConfig().getInt("countdown-seconds", 0));
                if (worldCountdown < 0 || worldCountdown > 60) {
                    getLogger().warning("Invalid countdown-seconds for world '" + worldName + "'; using global value.");
                    getConfig().set(base + "countdown-seconds", getConfig().getInt("countdown-seconds", 0));
                }
                long worldCooldown = getConfig().getLong(base + "cooldown-seconds",
                        getConfig().getLong("cooldown-seconds", 30));
                if (worldCooldown < 0 || worldCooldown > 86400) {
                    getLogger().warning("Invalid cooldown-seconds for world '" + worldName + "'; using global value.");
                    getConfig().set(base + "cooldown-seconds", getConfig().getLong("cooldown-seconds", 30));
                }
                int worldAttempts = getConfig().getInt(base + "maximum-attempts",
                        getConfig().getInt("maximum-attempts", 30));
                if (worldAttempts < 1 || worldAttempts > 200) {
                    getLogger().warning("Invalid maximum-attempts for world '" + worldName + "'; using global value.");
                    getConfig().set(base + "maximum-attempts", getConfig().getInt("maximum-attempts", 30));
                }
                int worldTimeout = getConfig().getInt(base + "search-timeout-seconds",
                        getConfig().getInt("search-timeout-seconds", 20));
                if (worldTimeout < 5 || worldTimeout > 120) {
                    getLogger().warning("Invalid search-timeout-seconds for world '" + worldName + "'; using global value.");
                    getConfig().set(base + "search-timeout-seconds", getConfig().getInt("search-timeout-seconds", 20));
                }
                World configuredWorld = Bukkit.getWorld(worldName);
                if (configuredWorld != null) {
                    int minY = getConfig().getInt(base + "minimum-y", configuredWorld.getMinHeight());
                    int maxY = getConfig().getInt(base + "maximum-y", configuredWorld.getMaxHeight() - 2);
                    if (minY < configuredWorld.getMinHeight() || minY > configuredWorld.getMaxHeight() - 2) {
                        getLogger().warning("Invalid minimum-y for world '" + worldName + "'; using world minimum height.");
                        getConfig().set(base + "minimum-y", configuredWorld.getMinHeight());
                        minY = configuredWorld.getMinHeight();
                    }
                    if (maxY < minY || maxY > configuredWorld.getMaxHeight() - 2) {
                        getLogger().warning("Invalid maximum-y for world '" + worldName + "'; using safe world ceiling.");
                        getConfig().set(base + "maximum-y", Math.max(minY, configuredWorld.getMaxHeight() - 2));
                    }
                }
            }
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

    private void runOnMainIfEnabled(Runnable action) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                if (isEnabled()) action.run();
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // Shutdown raced with an asynchronous completion; onDisable cleans up state.
        }
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

    private void loadCooldowns() {
        cooldownFile = new File(getDataFolder(), "cooldowns.yml");
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder; cooldown persistence may be unavailable.");
        }
        cooldownData = YamlConfiguration.loadConfiguration(cooldownFile);
        long now = System.currentTimeMillis();
        for (String key : cooldownData.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                long timestamp = cooldownData.getLong(key, 0L);
                // Ignore invalid/future timestamps and old entries to keep the file tidy.
                if (timestamp > 0 && timestamp <= now && now - timestamp <= 7L * 24 * 60 * 60 * 1000) {
                    cooldowns.put(id, timestamp);
                }
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Ignoring invalid UUID in cooldowns.yml: " + key);
            }
        }
    }

    private void saveCooldowns() {
        if (cooldownFile == null) return;
        if (cooldownData == null) cooldownData = new YamlConfiguration();
        cooldownData.getKeys(false).forEach(key -> cooldownData.set(key, null));
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= 0 || entry.getValue() > now
                || now - entry.getValue() > 7L * 24 * 60 * 60 * 1000);
        cooldowns.forEach((id, timestamp) -> cooldownData.set(id.toString(), timestamp));
        try {
            cooldownData.save(cooldownFile);
        } catch (IOException exception) {
            getLogger().warning("Could not save RTP cooldowns: " + exception.getMessage());
        }
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