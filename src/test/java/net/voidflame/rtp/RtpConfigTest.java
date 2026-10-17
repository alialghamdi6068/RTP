package net.voidflame.rtp;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RtpConfigTest {

    private YamlConfiguration loadDefaultConfig() {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream, "config.yml must be included in the plugin resources");
        try (stream; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception exception) {
            fail("Default config.yml must be valid YAML: " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    @Test
    void defaultTeleportLimitsAreSafeAndConsistent() {
        YamlConfiguration config = loadDefaultConfig();

        assertEquals(5, config.getInt("countdown-seconds"));
        assertEquals(30, config.getInt("cooldown-seconds"));
        assertTrue(config.getInt("minimum-radius") >= 0);
        assertTrue(config.getInt("maximum-radius") > config.getInt("minimum-radius"));
        assertEquals(30, config.getInt("maximum-attempts"));
        assertTrue(config.getInt("maximum-attempts") >= 1);
        assertTrue(config.getInt("maximum-attempts") <= 200);
        assertTrue(config.getInt("maximum-active-searches") >= 1);
        assertTrue(config.getInt("maximum-active-searches") <= 500);
        assertTrue(config.getInt("search-timeout-seconds") >= 5);
        assertTrue(config.getInt("search-timeout-seconds") <= 120);
        assertFalse(config.getBoolean("load-chunks"), "Chunk generation should be opt-in by default");
        assertTrue(config.getBoolean("avoid-water"), "Water should be rejected by default");
    }

    @Test
    void playerMessagesHavePrefixAndRequiredPlaceholders() {
        YamlConfiguration config = loadDefaultConfig();

        assertTrue(config.getString("messages.prefix", "").contains("RTP"));
        assertTrue(config.getString("messages.countdown-start", "").contains("%time%"));
        assertTrue(config.getString("messages.cooldown", "").contains("%time%"));

        String success = config.getString("messages.success", "");
        assertTrue(success.contains("%x%"));
        assertTrue(success.contains("%y%"));
        assertTrue(success.contains("%z%"));
        assertTrue(success.contains("%world%"));

        assertFalse(config.getString("messages.busy", "").isBlank());
        assertFalse(config.getString("messages.timeout", "").isBlank());
        assertFalse(config.getString("messages.failed", "").isBlank());
    }

    @Test
    void playerMenuAndPerWorldSettingsHaveSafeDefaults() {
        YamlConfiguration config = loadDefaultConfig();
        assertEquals(27, config.getInt("menu.size", 27));
        assertEquals(11, config.getInt("menu.world-slots.world", 11));
        assertEquals(13, config.getInt("menu.world-slots.world_nether", 13));
        assertEquals(15, config.getInt("menu.world-slots.world_the_end", 15));
        assertTrue(config.getConfigurationSection("world-settings") == null
                || config.getConfigurationSection("world-settings").getKeys(false).isEmpty());
        assertEquals(22, config.getInt("menu.close-slot", 22));
    }

    @Test
    void customWorldAreasDefaultToEmptyAndCanBeConfiguredPerWorld() {
        YamlConfiguration config = loadDefaultConfig();

        assertTrue(config.getConfigurationSection("world-areas") == null
                || config.getConfigurationSection("world-areas").getKeys(false).isEmpty());

        config.set("world-areas.world.min-x", -3000);
        config.set("world-areas.world.max-x", 3000);
        config.set("world-areas.world.min-z", -2000);
        config.set("world-areas.world.max-z", 2000);

        assertEquals(-3000, config.getInt("world-areas.world.min-x"));
        assertEquals(3000, config.getInt("world-areas.world.max-x"));
        assertEquals(-2000, config.getInt("world-areas.world.min-z"));
        assertEquals(2000, config.getInt("world-areas.world.max-z"));
    }
}
