## DeadChest - Configuration

Make sure you have [installed](installation.md) the plugin before editing the configuration.

You can edit configuration by making change on `config.yml` file on the plugin folder

DeadChest now uses a structured `config.yml` (schema version `2`) since `v5.0.0`.
After any change, run `/dc reload`.

### Global

| Key              | Type    | Default | Description                   |
|------------------|---------|---------|-------------------------------|
| `config-version` | integer | `2`     | Configuration schema version. |

### Localization

| Key                     | Type   | Default | Description                                                                                                                            |
|-------------------------|--------|---------|----------------------------------------------------------------------------------------------------------------------------------------|
| `localization.language` | string | `en`    | Language file loaded from `plugins/DeadChest/localization/<language>.json` (ex: `en`, `fr`, `es`, `de`, `pt-br`, `pl`, `it`, `zh-cn`). |

### Updates

| Key                  | Type    | Default | Description                                |
|----------------------|---------|---------|--------------------------------------------|
| `updates.auto-check` | boolean | `true`  | Enable automatic update checks at startup. |

### Chest

| Key                           | Type    | Default                 | Description                                                                 |
|-------------------------------|---------|-------------------------|-----------------------------------------------------------------------------|
| `chest.owner-only-open`       | boolean | `true`                  | During the private phase: only owner can open chest (except `deadchest.chestPass`). |
| `chest.duration-seconds`      | integer | `300`                   | Duration of the private phase in seconds. `0` = infinite private phase.     |
| `chest.indestructible`        | boolean | `true`                  | Protect chest block from destruction/explosions.                            |
| `chest.max-per-player`        | integer | `15`                    | Max active chests per player. `0` = unlimited.                              |
| `chest.replace-oldest`        | boolean | `false`                 | At the limit: replace the oldest chest (`true`) or create none (`false`). The replaced content follows the expiration drop setting. |
| `chest.recovery-mode`         | string  | `inventory-then-ground` | `inventory-then-ground` or `ground-drop`.                                   |
| `chest.block-type`            | string  | `chest`                 | `chest`, `player-head`, `barrel`, `shulker-box`, `ender-chest`.             |
| `chest.drop-items-on-timeout` | boolean | `false`                 | Legacy one-phase timeout behavior when `chest.loot.enabled=false`: drop items (`true`) or remove contents (`false`). |

### Chest - Public Loot Phase

| Key                                      | Type    | Default | Description |
|------------------------------------------|---------|---------|-------------|
| `chest.loot.enabled`                     | boolean | `false` | Enable a second public loot phase after the private phase ends. |
| `chest.loot.public-duration-seconds`     | integer | `300`   | Duration of the public phase in seconds. `0` = infinite public phase. |
| `chest.loot.drop-items-on-timeout`       | boolean | `false` | At the end of the public phase: drop items (`true`) or remove contents (`false`). |
| `chest.loot.public-access.owner`         | boolean | `true`  | During public phase, allow the owner to recover the chest. |
| `chest.loot.public-access.killer`        | boolean | `true`  | During public phase, allow the PvP killer to loot the chest. |
| `chest.loot.public-access.other-players` | boolean | `true`  | During public phase, allow any other player to loot the chest. |

The timeout model is therefore:

- Phase 1: private phase controlled by `chest.duration-seconds`.
- Phase 2: optional public phase controlled by `chest.loot.*`.
- If `chest.loot.enabled=false`, the chest expires directly after the private phase and uses `chest.drop-items-on-timeout`.
- If `chest.loot.enabled=true`, the chest enters the public phase after the private phase, then expires using `chest.loot.drop-items-on-timeout`.

### Vanilla Drop Mode

Turns DeadChest off as a chest plugin: no chest, no hologram, no stored inventory.
Items are spread on the ground exactly like vanilla, but they stay reserved for the player who died and are protected from the vanilla despawn timer.

| Key                                  | Type    | Default | Description                                                                                                    |
|--------------------------------------|---------|---------|----------------------------------------------------------------------------------------------------------------|
| `vanilla-drop.enabled`               | boolean | `false` | Disable DeadChest generation and keep vanilla death drops instead.                                              |
| `vanilla-drop.worlds`                | list    | `[]`    | Where the mode applies. Empty = everywhere. An entry is a world name or a whole dimension (`OVERWORLD`, `NETHER`, `END`). A world left out falls back to the normal DeadChest behavior. |
| `vanilla-drop.rescue-void-deaths`    | boolean | `true`  | Bring the items of a death below the world back to the surface. `false` leaves them to the void, which destroys them exactly like vanilla Minecraft does. |
| `vanilla-drop.owner-only-pickup`     | boolean | `true`  | Only the dead player can pick the drops up. Mobs and hoppers are blocked too. `false` leaves the drops open to everybody, mobs and hoppers included. |
| `vanilla-drop.despawn-seconds`       | integer | `300`   | Lifetime of the reserved drops, counted in real time. `0` = never disappear.                                    |
| `vanilla-drop.protect-from-despawn`  | boolean | `true`  | Cancel the vanilla despawn timer so only `despawn-seconds` applies. `false` lets the vanilla timer remove the drops, whichever of the two comes first. |
| `vanilla-drop.invulnerable`          | boolean | `false` | Make reserved drops immune to fire, lava, explosions and cactus.                                                |
| `vanilla-drop.glow`                  | boolean | `false` | Add a glowing outline on reserved drops.                                                                        |

What still works in this mode:

- `messages.display-position-on-death` sends the coordinates of the drops on death.
- `respawn.compass` hands out the compass on respawn as usual, pointing at the reserved drops instead of a chest. It is
  retargeted, and removed once every drop has been picked up or expired, exactly like it is for a chest.
- `integrity.crash-protection` covers the reserved drops too: the death is stamped on the player data before the items leave
  the inventory, and a server killed without a clean shutdown can no longer leave the items both in the inventory and on the
  ground. `integrity.flush-player-data` applies the same way as for chests, and a proven duplicate is always destroyed.
- The vanilla recovery compass keeps pointing at the death location, the plugin never cancels the death itself.
- `filters.ignored-items` entries are left to vanilla or to another plugin, they are never locked.
- `pvp.keep-inventory-on-player-kill` is answered before this mode, so a player kill keeps the inventory and reserves
  nothing.

What this mode deliberately leaves alone:

The point of the mode is that the items behave the way the game makes them behave. Ownership and the lifetime are the only
deviations, so the options DeadChest uses to rework what a player loses are **not** applied here, by design:

| Key                                  | In this mode                                                                                  |
|--------------------------------------|-----------------------------------------------------------------------------------------------|
| `filters.excluded-items`             | Not applied. The items drop, vanilla decides. In chest mode they are destroyed on death.        |
| `durability.loss-on-death-percent`   | Not applied. A death costs no extra durability, exactly like vanilla.                           |
| `xp.store-on-death`, `xp.store-percentage` | Not applied. Experience drops the way the game drops it.                                  |
| `permissions.require-generate`       | Not applied. There is no grave to authorize, and vanilla does not ask for a permission to drop. |
| `chest.max-per-player`, `chest.replace-oldest` | Not applied. There is no chest to count.                                            |

Set them for the chest mode; a world that falls back to a grave through `vanilla-drop.worlds` gets all of them as usual.

- `filters.excluded-worlds`, `generation.allow-in-end-worlds` and `generation.allow-in-creative` say where a **grave** may
  be placed and have no effect on this mode. Use `vanilla-drop.worlds` to scope it.

Notes:

- The countdown uses real time, so it keeps running while the chunk is unloaded or while nobody is nearby: a drop is removed `despawn-seconds` after the death, wherever the player is.
- The vanilla despawn timer this protects against is `item-despawn-rate` in `spigot.yml`, not a fixed 5 minutes. It is
  `6000` ticks out of the box but plenty of servers lower it, and a server sitting at `3000` removes untouched items after
  2 minutes 30. With `protect-from-despawn: true` the reserved drops are taken off that timer entirely, so
  `despawn-seconds` applies whatever the server rate is. It only bites with `protect-from-despawn: false`, or for the
  items DeadChest never took over (`filters.ignored-items`), which keep the server rate.
- Reserved drops are tagged on the item entity, so a chunk unload or a server restart does not release them (requires Minecraft 1.14+, older servers only keep the lock until the next restart).
- The reservation is written on the item entity itself, where Minecraft enforces it: the server refuses to hand a reserved drop to another player even if no plugin is listening. DeadChest also cancels the pickup events, which covers mobs and hoppers, and it does so last so another plugin cannot undo it.
- Walking away is safe: an unloaded chunk saves its items to disk like vanilla, and the drops are found back when the chunk (or, on Paper 1.17+, its entity storage) is loaded again. Only the lifetime can remove them.
- Bypass permissions: `deadchest.dropPass` and `deadchest.chestPass`.
- Turning the mode off later does not release the drops already on the ground: they keep their lock and their timer.
- Chest-only options are not applied in this mode: `filters.excluded-items`, `durability.loss-on-death-percent`, `xp.store-on-death` and every `chest.*` key. Items and XP follow vanilla rules.

### Permissions

| Key                            | Type    | Default | Description                                            |
|--------------------------------|---------|---------|--------------------------------------------------------|
| `permissions.require-generate` | boolean | `false` | Require `deadchest.generate` to create chest on death. |
| `permissions.require-claim`    | boolean | `false` | Require `deadchest.get` to claim/retrieve chest.       |
| `permissions.require-list-own` | boolean | `false` | Require `deadchest.list.own` for `/dc list`.           |

### Maintenance

| Key                              | Type    | Default | Description                              |
|----------------------------------|---------|---------|------------------------------------------|
| `maintenance.cleanup-on-startup` | boolean | `false` | Remove all DeadChests on server startup. |

### Respawn Compass

| Key                              | Type    | Default | Description                                                                 |
|----------------------------------|---------|---------|-----------------------------------------------------------------------------|
| `respawn.compass`                | boolean | `true`  | Give a compass pointing at the latest DeadChest when a player respawns.     |
| `respawn.compass-update-seconds` | integer | `5`     | How often the compass is retargeted. Applied at server start.               |

The compass is a plugin item, not loot:

- it always points at the player's most recent DeadChest, and is retargeted every
  `compass-update-seconds`;
- it cannot be dropped, and cannot be moved into a container;
- it is never stored inside a DeadChest and never appears in the death drops;
- it disappears as soon as the player has no DeadChest left.

### Placement

| Key                                   | Type    | Default | Description                                                                     |
|---------------------------------------|---------|---------|---------------------------------------------------------------------------------|
| `generation.placement.safe-location`  | boolean | `true`  | Only create chests where the owner may build (protection plugins are asked).     |
| `generation.placement.ground`         | boolean | `true`  | A chest created in the air falls to the ground.                                  |
| `generation.placement.void`           | boolean | `true`  | A death in the void uses the closest real block, or plain air when there is none.|
| `generation.placement.lava-top`       | boolean | `true`  | A death in lava floats the chest to the lava surface.                            |
| `generation.placement.lava-smart`     | boolean | `true`  | When the lava surface is covered, use the last block the entity stood on.        |
| `generation.placement.water-top`      | boolean | `false` | Drowning floats the chest to the water surface.                                  |
| `generation.placement.water-bottom`   | boolean | `true`  | Drowning sinks the chest to the bottom. Also applied when `ground` is enabled.   |
| `generation.placement.suffocation`    | boolean | `true`  | Suffocating uses the last solid block, then above or below the column.           |
| `generation.placement.powder-snow`    | boolean | `true`  | Dying in powder snow uses the last solid block, then the snow surface.           |
| `generation.placement.search-radius`  | integer | `6`     | How far to look for a valid block when the resolved position cannot be used.     |

These options never compete with each other, they form one chain:

1. **The death context selects exactly one rule**, in this order:
   `void` → `lava` → `water` → `powder-snow` → `suffocation` → `ground`.
   A player who drowns is only handled by the water rules, a player who falls in
   the void only by the void rule, and so on.
2. **The position it returns is then validated**: clamped inside the world border
   and the build height, and accepted only if the block is free, not already used
   by another DeadChest, and buildable by the owner when `safe-location` is on.
3. **If it is not valid, the closest usable block is searched**, starting straight
   below the death position, then straight above, then in rings up to
   `search-radius` blocks away.
4. **If nothing valid exists**, no DeadChest is created and the items are dropped
   by vanilla rather than being placed somewhere unreachable.

Because of step 2, two entities dying on the same block always get two different
DeadChests: the second one is moved to the closest free block instead of
overwriting the first.

### Integrity (crash duplication protection)

| Key                             | Type    | Default | Description                                                                                     |
|---------------------------------|---------|---------|-------------------------------------------------------------------------------------------------|
| `integrity.crash-protection`    | boolean | `true`  | Detect and cancel the item duplication caused by a server killed without a clean shutdown.       |
| `integrity.flush-player-data`   | boolean | `true`  | Write the player data to disk as soon as items move between a player and a chest.                |

The plugin database is written the moment a player dies, while the vanilla
`playerdata/<uuid>.dat` file is only written on autosave, on quit or on a clean
shutdown. When the server is killed without shutting down (out of memory kill,
`kill -9`, host crash), the two disagree: the DeadChest survives with the items,
while the player file rolls back to before the death and the player logs back in
with the same items still in the inventory.

With `crash-protection` enabled, every transfer between a player and a chest is
stamped on both sides and only completed once the player data reached the disk.
A transfer left half done by a crash is settled when the player reconnects:

- the death never reached the player file: the player already owns the items, the
  chest is a duplicate and is always removed;
- the hand over never reached the player file: the player never kept the items,
  the chest comes back with its content.

Until that decision can be made, the chest stays locked: it cannot be opened, it
does not expire and it does not drop its content.

### Generation Rules

| Key                              | Type    | Default | Description                                       |
|----------------------------------|---------|---------|---------------------------------------------------|
| `generation.allow-in-creative`   | boolean | `true`  | Allow chest generation on death in Creative mode. |
| `generation.allow-on-lava`       | boolean | `true`  | Allow generation when death occurs in lava.       |
| `generation.allow-on-water`      | boolean | `true`  | Allow generation when death occurs in water.      |
| `generation.allow-on-rails`      | boolean | `true`  | Allow generation when death occurs on rails.      |
| `generation.allow-in-minecart`   | boolean | `true`  | Allow generation when death occurs in minecart.   |
| `generation.allow-in-end-worlds` | boolean | `true`  | Allow generation in The End worlds.               |

### Messages

| Key                                  | Type    | Default | Description                                |
|--------------------------------------|---------|---------|--------------------------------------------|
| `messages.display-position-on-death` | boolean | `true`  | Send chest coordinates to player on death. |

### XP

| Key                   | Type    | Default | Description                                                              |
|-----------------------|---------|---------|--------------------------------------------------------------------------|
| `xp.store-on-death`   | boolean | `false` | Store XP in chest instead of normal orb drop.                            |
| `xp.store-percentage` | integer | `100`   | XP percent stored when enabled (`0` to `100`, values >100 duplicate XP). |

### PvP

| Key                                 | Type    | Default | Description                                                 |
|-------------------------------------|---------|---------|-------------------------------------------------------------|
| `pvp.keep-inventory-on-player-kill` | boolean | `false` | On PvP death: keep inventory and skip DeadChest generation. |

### Integrations

| Key                                     | Type    | Default | Description                                        |
|-----------------------------------------|---------|---------|----------------------------------------------------|
| `integrations.worldguard.enabled`       | boolean | `false` | Enable WorldGuard region checks.                   |
| `integrations.worldguard.default-allow` | boolean | `false` | Default region policy if no DeadChest flag is set. |

### Durability

| Key                                | Type    | Default | Description                                                            |
|------------------------------------|---------|---------|------------------------------------------------------------------------|
| `durability.loss-on-death-percent` | integer | `0`     | Durability loss on death (percentage of max durability). `0` disables. |

### Logging

| Key                                   | Type    | Default | Description                         |
|---------------------------------------|---------|---------|-------------------------------------|
| `logging.deadchest-create-to-console` | boolean | `false` | Log each chest creation in console. |

### Visuals - Effect Animation

| Key                                | Type    | Default | Description                                      |
|------------------------------------|---------|---------|--------------------------------------------------|
| `visuals.effect-animation.enabled` | boolean | `true`  | Enable orbit particles around active DeadChests. |
| `visuals.effect-animation.style`   | string  | `ender` | Effect style: `soul`, `flame`, `ender`.          |
| `visuals.effect-animation.radius`  | number  | `0.8`   | Orbit radius around chest center.                |
| `visuals.effect-animation.speed`   | number  | `1.1`   | Orbit speed multiplier.                          |

DeadChest holograms now use three lines when available:

- top line: access state (`PRIVATE`, `OPEN`, `PUBLIC`, `KILLER`, `OWNER`, `SHARE`)
- middle line: owner name
- bottom line: remaining time

The access-state line reflects both the private/public phase and the configured access rules during the public phase.

### Visuals - Pickup Animation

| Key                                 | Type    | Default    | Description                                     |
|-------------------------------------|---------|------------|-------------------------------------------------|
| `visuals.pickup-animation.enabled`  | boolean | `true`     | Enable animation when a player claims a chest.  |
| `visuals.pickup-animation.particle` | string  | `FIREWORK` | Bukkit particle name used for pickup animation. |
| `visuals.pickup-animation.count`    | integer | `22`       | Number of particles spawned.                    |
| `visuals.pickup-animation.offset-x` | number  | `0.45`     | Particle spread on X axis.                      |
| `visuals.pickup-animation.offset-y` | number  | `0.5`      | Particle spread on Y axis.                      |
| `visuals.pickup-animation.offset-z` | number  | `0.45`     | Particle spread on Z axis.                      |
| `visuals.pickup-animation.speed`    | number  | `0.08`     | Particle extra/speed value.                     |
| `visuals.pickup-animation.y-shift`  | number  | `0.55`     | Vertical center offset from block base.         |

### Visuals - Pickup Sound

| Key                            | Type    | Default                 | Description                   |
|--------------------------------|---------|-------------------------|-------------------------------|
| `visuals.sound.pickup.enabled` | boolean | `true`                  | Enable sound on chest pickup. |
| `visuals.sound.pickup.name`    | string  | `ENTITY_PLAYER_LEVELUP` | Bukkit sound name.            |
| `visuals.sound.pickup.volume`  | number  | `1.2`                   | Sound volume.                 |
| `visuals.sound.pickup.pitch`   | number  | `1.0`                   | Sound pitch.                  |

### Filters

| Key                       | Type         | Default        | Description                                                           |
|---------------------------|--------------|----------------|-----------------------------------------------------------------------|
| `filters.excluded-worlds` | list<string> | Example values | Worlds where DeadChest generation is disabled.                        |
| `filters.excluded-items`  | list<string> | Example values | Items that are not stored in DeadChest.                               |
| `filters.ignored-items`   | list<string> | Example values | Items ignored by DeadChest storage; they keep vanilla death behavior. |

`filters.ignored-items` and `/dc ignore` use the same source of truth:

- `filters.ignored-items` is the canonical list in `config.yml`.
- `/dc ignore` edits that same list through a GUI.
- A rule can be either a Bukkit `Material` name or a serialized `ItemStack`.
- Serialized `ItemStack` rules preserve custom meta and are written automatically by `/dc ignore`.
- Ignored items are not saved in a DeadChest and are left to vanilla death handling or other plugins.

### Permission reference

- `deadchest.admin`: admin commands (`reload`, `repair`, `removeall`, `removeinfinite`, `ignore`)
- `deadchest.generate`: generate chest on death (if required)
- `deadchest.get`: claim/retrieve chest (if required)
- `deadchest.list.own`: list own chests (if required)
- `deadchest.list.other`: list another player's chests
- `deadchest.remove.own`: remove own chests
- `deadchest.remove.other`: remove another player's chests
- `deadchest.giveback`: give back another player's items
- `deadchest.chestPass`: bypass owner-only chest access
- `deadchest.dropPass`: pick up drops reserved to another player in vanilla drop mode
- `deadchest.infinityChest`: create infinite chests

