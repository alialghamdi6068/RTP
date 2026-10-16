# RTP

A configurable random-teleport plugin for **Paper 26.2** and **Java 25**. Designed for servers that need safe destinations, configurable world boundaries, player-friendly feedback, and bounded search work.

## Features

- Player-only, private RTP feedback with configurable color-coded messages.
- Configurable countdown and per-player cooldown.
- Countdown cancellation when a player changes block; looking around does not cancel it. Cancelled countdowns refund the cooldown.
- Per-world rectangular X/Z areas, or a radius range around the destination world's spawn.
- World allow/deny lists and operator GUIs for world management and live settings (countdown, cooldown, attempts, concurrent searches, search timeout, chunk loading, and water avoidance).
- Safe destination checks for solid ground, two passable blocks, hazardous blocks, world height, and world border.
- Unloaded chunks are skipped by default. When chunk loading is enabled, Paper's asynchronous chunk API is used instead of synchronously generating a chunk on the server thread.
- Configurable cap on concurrent searches and a hard upper bound on candidate attempts.
- Configuration sanity checks on startup and `/rtp reload`.
- GitHub Actions build that packages `target/RTP.jar`.

## Requirements

- Minecraft server running Paper 26.2 (matching the Paper API targeted by this build).
- Java 25 at runtime and for building.

## Install

1. Download the `RTP.jar` artifact from a successful [GitHub Actions build](https://github.com/alialghamdi6068/RTP/actions).
2. Put `RTP.jar` into the server's `plugins` directory.
3. Start the server once to generate `plugins/RTP/config.yml`.
4. Adjust the configuration if needed, then run `/rtp reload` or restart.

Build locally with JDK 25:

```bash
mvn --batch-mode clean verify
```

The packaged JAR is `target/RTP.jar`.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/rtp` | Start random teleport | `rtp.use` |
| `/randomtp` | Alias for `/rtp` | `rtp.use` |
| `/rtp gui` | Open the RTP Control Center | `rtp.admin` |
| `/rtp worlds` | Open world management directly | `rtp.admin` |
| `/rtp reload` | Reload and validate configuration | `rtp.admin` |

### Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `rtp.use` | Everyone | Use RTP |
| `rtp.admin` | Operators | Manage worlds, use the admin GUI, and reload settings |
| `rtp.bypasscooldown` | Operators | Bypass the cooldown |

## Configuration

Main settings in `plugins/RTP/config.yml`:

| Setting | Default | Notes |
| --- | ---: | --- |
| `world` | `""` | Empty uses the player's current world as the destination |
| `minimum-radius` | `100` | Minimum distance from world spawn when no custom area is configured |
| `maximum-radius` | `5000` | Maximum distance from world spawn when no custom area is configured |
| `countdown-seconds` | `5` | Allowed range: 1–60 seconds |
| `cooldown-seconds` | `30` | Allowed range: 0–86400 seconds |
| `maximum-attempts` | `20` | Allowed range: 1–200 candidate locations per request |
| `maximum-active-searches` | `25` | Allowed range: 1–500 simultaneous searches |
| `search-timeout-seconds` | `20` | Allowed range: 5–120 seconds before a stalled search is stopped |
| `load-chunks` | `false` | If enabled, unloaded chunks may be generated asynchronously |
| `avoid-water` | `true` | Reject water and bubble columns |

`allowed-worlds: []` means any source world is allowed unless disabled. Worlds listed in `disabled-worlds` cannot be used as a source or destination.

### Custom world areas

A custom rectangle overrides the radius range for that destination world. X/Z bounds are inclusive block coordinates; Y is detected from the terrain.

```yaml
world-areas:
  world:
    min-x: -3000
    max-x: 3000
    min-z: -2000
    max-z: 2000
  world_nether:
    min-x: -1000
    max-x: 1000
    min-z: -1000
    max-z: 1000
```

Ensure all four values are present and each minimum is less than or equal to its maximum.

### Messages

All player-facing messages are under `messages:`. Use `&` color codes and preserve placeholders such as `%time%`, `%x%`, `%y%`, `%z%`, and `%world%`. The `messages.prefix` value is added to plugin chat messages. RTP search and result feedback is sent only to the requesting player.

## Performance and safety notes

- Keep `load-chunks: false` for the lowest world-generation impact. If enabled, chunk generation can still create disk and CPU load even when performed asynchronously.
- `maximum-attempts`, `maximum-active-searches`, and `search-timeout-seconds` bound the amount and duration of search work requested through the plugin.
- Each search has a unique request token so a delayed chunk callback from an expired request cannot interfere with a newer request by the same player.
- RTP safety checks are best-effort. Server owners should still configure world borders, protected regions, and spawn protection appropriately.
- This plugin does not provide network-level DDoS protection.

## Tests and CI

The Maven test suite validates the default configuration, required message placeholders, and custom-area configuration behavior. GitHub Actions runs `mvn --batch-mode --no-transfer-progress clean verify` on pushes and pull requests to `main`, then uploads the plugin JAR as an artifact when verification succeeds.

A successful compilation and the automated tests do not replace an in-game Paper 26.2 smoke test before production deployment.

## License

MIT
