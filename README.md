# RTP

A configurable, lightweight random teleport plugin for Paper 26.2 and Java 25.

## Install

Build with JDK 25 using `mvn clean package`, then place `target/RTP.jar` in the server's `plugins` folder and restart.

## Commands

| Command | Description |
| --- | --- |
| `/rtp` | Start random teleport |
| `/rtp gui` | Open the RTP Control Center GUI (operators/admins) |
| `/rtp worlds` | Open the world-management GUI directly |
| `/rtp reload` | Reload configuration |
| `/randomtp` | Alias for `/rtp` |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `rtp.use` | Everyone | Use RTP |
| `rtp.admin` | Operators | Manage world access and reload |
| `rtp.bypasscooldown` | Operators | Bypass cooldown |

## Custom world area

Configure rectangle bounds for each destination world in `plugins/RTP/config.yml`. Coordinates are block X/Z values; Y is detected from the surface.

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

A configured rectangle replaces the radius settings for that world. Otherwise, `minimum-radius` and `maximum-radius` define a ring around world spawn.

## Behavior and performance

- Five-second countdown by default. Moving to another block cancels the request; looking around does not.
- Thirty-second cooldown per player, starting when the countdown begins.
- Chunk loading/generation is disabled by default to avoid lag. The plugin skips unloaded chunks unless `load-chunks: true` is configured.
- Safe-location checks reject unsafe ground and require two passable blocks above it.
- `maximum-attempts` limits checks per request.

## License

MIT
