# Ping For It

Ping For It is a target-aware fork of Ping Wheel: point at a block or entity,
hold the ping key when a choice is needed, and show friends what you mean.

![Pinging blocks](.github/in-game-pinging-blocks.png)

## Release

- Version: `0.4.1-pfi-beta1`
- Minecraft: `1.21.1`
- Java: `21`
- Loaders: Fabric, Forge, and NeoForge
- Mod ID: `pingforit`

## Pinging

The target and ray result are captured on the initial key press and are not
retargeted while the interaction is in progress. Entity identity follows the
same-dimension entity; block identity keeps its dimension, position, and block
type. The complete press-time capture and freezing contract is owned by
[Capture](docs/architecture/picking/capture.md).

There are seven predefined ping types: **Attention**, **Danger**, **Go To**,
**Loot**, **Destroy**, **Take**, and **Request**. A short press uses the
captured target type's default. Holding the key opens a wheel for that target's
available types; the center cancels the nearest eligible marker owned by you;
the wheel can also time out without acting. The authoritative type catalogue is
owned by [Catalogs](docs/architecture/identity/catalogs.md).

Markers, target validation, ownership, shared-target winner selection, and rate
limiting are server-authoritative. The client only mirrors the server's create
rate policy as a courtesy; a throttled create is dropped rather than queued.

## Rendering

Entity markers outline ordinary entities and dropped items in the selected ping
color. Block outlines use the block's native shape, including non-full-cube
geometry, with a through-wall outline. A client-configurable display whitelist
and shape blacklist control which blocks are outlined; the exact selector
grammar, list defaults, and entity-block geometry modes are owned by the
[client configuration](docs/config/client.md) and
[VoxelShape geometry](docs/architecture/geometry/voxel_shape.md) contracts.

On NeoForge, optional Create support can render an entity-block silhouette mask.
It loads lazily and is a soft, compile-only integration; missing Create or
Flywheel does not disable ordinary pings. Current optional-version gates and
routes are owned by the [Create integration](docs/integrations/create.md) and
[compatibility](docs/compatibility.md).

## Languages

Eight bundled locales are supported: `en_us`, `de_de`, `es_ar`,
`fr_fr`, `pl_pl`, `tr_tr`, `zh_cn`, and `zh_tw`. Ping chat, target names,
settings, and wheel text use Minecraft localization.

## Controls and commands

| Action | Default |
| --- | --- |
| Ping | Mouse5 (rebindable) |
| Open settings | Unbound (rebindable) |

The ping key's short release sends the default type; holding it opens the
wheel. The wheel center is the cancel action, not another ping type.

- `/pingforit` or `/pingforit help` shows command help.
- `/pingforit config` opens the settings GUI. Its server section is available
  to players with the required server permission and edits authoritative server
  settings; the exact edit authority and required permission are owned by
  [Server configuration authority](docs/architecture/authority/server-config.md).
- `/pingforit channel` reads or changes the player's ping channel.

## Configuration

The client and server settings are stored in configurable JSON files under
`config/`. The settings GUI includes client controls and, when permitted, the
server section; it has no GUI list editor, and its block-list action saves and
closes the screen before opening the client file. Exact filenames, keys, list
syntax, close/save behavior, external-edit reload timing, and reset semantics
are owned by the [client configuration](docs/config/client.md),
[server configuration](docs/config/server.md), and
[configuration UI](docs/UI/settings-screen.md) contracts.

## Install, build, and verify

Install the jar for your loader and Minecraft `1.21.1`; Fabric also requires
Fabric API. Optional mods and registry content are soft dependencies.

From the repository root with JDK 21, build and verify the three loader jars:

```powershell
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'
.\gradlew.bat --no-daemon build
.\gradlew.bat --no-daemon verifyModIdentity
```

## Beta status and attribution

This is a beta release. Rendering, multiplayer synchronization, optional
integration behavior, and hold/wheel interaction still need more in-game
validation on supported loaders; build checks do not replace that validation. 
So do I and my friends.

The fork retains attribution to LukenSkyne's original Ping Wheel work. It has
its own identity and protocol, does not migrate the original protocol, does not
declare or enforce a conflict block against the original mod, and gives no
interoperability guarantee when both are installed.

## Special Thanks

- **LukenSkyne** and other Ping Wheel contributers for creating the original work
- **SHARK_oi** for providing this simple icon when I'm hesitating.
