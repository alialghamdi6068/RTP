# RTP

A configurable random-teleport plugin for **Paper 26.2**. Players use `/rtp` to teleport to a random safe location. Administrators can manage per-world availability through an in-game GUI.

## Requirements

- Paper 26.2
- Java 25
- Maven 3.9+ to build from source

## Install

1. Download `RTP.jar` from Releases when a release is published, or build it from source.
2. Put `RTP.jar` in the server's `plugins` folder.
3. Restart the server.
4. Edit `plugins/RTP/config.yml` if needed.

## Build from source

Use JDK 25 and run:

```bash
mvn clean package
```

The output JAR is `target/RTP.jar`.

## Commands

| Command | Description |
| --- | --- |
| `/rtp` | Teleport to a random safe location |
| `/rtp gui` | Open the world-management GUI |
| `/rtp worlds` | Alias for the world-management GUI |
| `/rtp reload` | Reload configuration |
| `/randomtp` | Alias for `/rtp` |

## World GUI

Run `/rtp gui` with `rtp.admin`. Click a world to enable or disable RTP there. Green dye means enabled; red dye means disabled. The GUI saves changes to `plugins/RTP/config.yml` immediately and supports multiple pages.

Disabling a world blocks RTP both when a player runs the command in that world and when it is configured as the destination world.

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `rtp.use` | Everyone | Use random teleport |
| `rtp.admin` | Operators | Open world GUI and reload configuration |
| `rtp.bypasscooldown` | Operators | Bypass cooldown |

## Configuration

- `world: ""`: use the player's current world; otherwise use the named destination world.
- `allowed-worlds: []`: when empty, players can run RTP from any world unless it is disabled.
- `disabled-worlds: []`: worlds where RTP cannot be used or selected as the destination.
- `minimum-radius` / `maximum-radius`: distance from the world's spawn point.
- `maximum-attempts`: safe locations checked per request.
- `load-chunks`: whether unloaded destination chunks may be loaded/generated. This can cause temporary lag.
- `avoid-water`: reject water locations.
- `cooldown-seconds`: default cooldown.
- `messages`: configurable player messages.

## Notes

The plugin checks for a solid floor, two passable blocks above it, and common unsafe blocks. RTP can fail if no safe location is found within the configured attempt limit. Test on a staging server before production deployment.

## License

MIT