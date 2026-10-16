# RTP

A lightweight, configurable random-teleport plugin for Paper servers. Players can use `/rtp` to teleport to a random safe location within a configured radius.

## Requirements

- Paper 1.21.11
- Java 21 or newer
- Maven 3.9+ to build from source

## Install

1. Download the compiled `RTP.jar` from the repository's Releases page when a release is published, or build it from source.
2. Put `RTP.jar` in your server's `plugins` folder.
3. Restart the server.
4. Edit `plugins/RTP/config.yml` if needed, then run `/rtp reload`.

## Build from source

```bash
mvn clean package
```

The output JAR is `target/RTP.jar`.

## Commands

| Command | Description |
| --- | --- |
| `/rtp` | Teleport to a random safe location |
| `/rtp reload` | Reload configuration (admin permission) |
| `/randomtp` | Alias for `/rtp` |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `rtp.use` | Everyone | Use random teleport |
| `rtp.admin` | Operators | Reload configuration |
| `rtp.bypasscooldown` | Operators | Bypass cooldown |

## Configuration

The generated `config.yml` includes the world, allowed worlds, minimum and maximum radius, number of attempts, chunk-loading behavior, water avoidance, cooldown, and all player-facing messages.

- Leave `world: ""` to use the player's current world.
- Leave `allowed-worlds: []` to allow RTP in any world.
- `maximum-radius` and `minimum-radius` are measured from the world's X/Z origin.
- `load-chunks` is disabled by default to avoid unexpectedly generating/loading many chunks. If RTP often cannot find a location in unexplored areas, enable it, noting that chunk generation can cause server lag.

## Notes

- The plugin checks for a solid floor, two passable blocks above it, and common unsafe blocks.
- RTP may fail if no safe location is found within the configured attempt limit.
- Test on a staging server before deploying to a busy production server.

## License

MIT