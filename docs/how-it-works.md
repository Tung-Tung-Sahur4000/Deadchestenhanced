## DeadChest - How It Works

This page explains how DeadChest behaves in common gameplay situations.

### Default behavior

When a player dies, DeadChest creates a protected chest at a suitable location near the death point.
By default:

- chest access starts in a private owner-only phase
- a hologram shows access state, owner, and remaining time
- the chest expires after configured timeout (unless infinite)

All behavior is configurable in [configuration](configuration.md).

### Timeout model

DeadChest supports two phases:

- Private phase: controlled by `chest.duration-seconds`
- Public loot phase: optional, controlled by `chest.loot.enabled` and `chest.loot.*`

If the public loot phase is disabled, the chest expires directly after the private phase.
If the public loot phase is enabled, the chest becomes public or semi-public after the private timer ends,
depending on the configured public access rules.

### Vanilla drop mode

With `vanilla-drop.enabled: true`, DeadChest stops creating chests entirely:

- items are spread on the ground at the death point, like vanilla
- the drops stay reserved for the dead player, nobody else, no mob and no hopper can take them
- the vanilla 5 minutes despawn timer is cancelled, `vanilla-drop.despawn-seconds` decides when the drops disappear
- that countdown runs in real time, so an unloaded chunk or a player far away does not freeze it
- the death coordinates message and the vanilla recovery compass still point to the drops

The lock is stored on the item entities themselves, so it survives a chunk unload and a server restart.

### Hologram states

The top hologram line represents the current access state:

- `PRIVATE`: owner-only private phase
- `OPEN`: private phase, but chest is open to everyone
- `PUBLIC`: public phase, everyone can loot
- `KILLER`: public phase reserved to the killer
- `OWNER`: public phase reserved to the owner
- `SHARE`: public phase with mixed/restricted access rules

### Chest placement rules

DeadChest never intentionally replaces solid blocks. If the exact death block is invalid, it searches for the nearest
valid location.

#### Overworld, Nether, End, custom worlds

The same placement logic is used in all worlds.

#### Death below the world

DeadChest clamps to the lowest valid height for that world at the same horizontal position.

#### Death above world max height

DeadChest clamps to the highest valid height for that world at the same horizontal position.

#### Death on non-placeable spots (doors, rails, torches, etc.)

If the exact block cannot host a chest, DeadChest searches for the next valid free space.

### Water and lava deaths

DeadChest can generate in these environments if enabled:

- `generation.allow-on-water`
- `generation.allow-on-lava`

### Item handling

- Items with Curse of Vanishing are not stored.
- Retrieval behavior depends on `chest.recovery-mode`.

### Protection and anti-grief behavior

Depending on config, DeadChests are protected against unauthorized access and destruction.
This includes owner checks and block protection logic.

### Performance model

DeadChest is designed for active servers and focuses work on loaded areas/chunks to limit overhead.

