## DeadChest - Architecture

This page is written for developers working on the plugin itself. It describes where the code lives, how a death is
processed, and which invariants must be preserved when changing things.

For user facing behavior see [How It Works](how-it-works.md) and [Configuration](configuration.md).

### Modules

| Module             | Contains                                                                 | Notes                                                                             |
|--------------------|--------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `deadchest-core`   | Every class of the plugin, plus the whole test suite                     | Compiled with `--release 8` so it keeps loading on old servers                     |
| `deadchest-plugin` | `DeadChest.java` (the `JavaPlugin`), `plugin.yml`, `config.yml`, locales | Shades `deadchest-core` and bStats into the published jar (`dead-chest-x.y.z.jar`) |

The split matters: **the core module has no `JavaPlugin`**, which is what makes the whole plugin testable under
MockBukkit. Only `deadchest-plugin` knows about the Bukkit plugin life cycle, and its single class does nothing but
delegate.

Build targets: Java 17 toolchain, main sources emitted as Java 8 bytecode, tests as Java 17. `deadchest-core` compiles
against `paper-api 1.20.4`, `deadchest-plugin` against `spigot-api 1.21`.

### Package map

```
me.crylonz.deadchest
├── DeadChestLoader          bootstrap: db, config, localization, listeners' shared state, repeating tasks
├── DeadChestManager         chest life cycle: expiration, holograms, timers, particles
├── DeadChestAPI             public API for other plugins
├── ChestData                one deadchest: inventory, position, owner, timers, integrity state
├── Localization             json language files with English fallback
├── commands/                /dc parsing, execution and tab completion
├── compass/                 respawn compass: what it points at and how it is maintained
├── db/                      SQLite storage, async executor, in memory cache
├── deps/worldguard/         soft dependency, loaded only when WorldGuard is present
├── drops/                   vanilla drop mode: reserved drops on the ground instead of a chest
├── integrity/               crash duplication protection shared by chests and drops
├── legacy/                  migration of the historic chestData.yml
├── listener/                every Bukkit listener (14 classes)
├── placement/               where a grave may be created, and what a grave block is
├── scheduler/               Bukkit and Folia scheduling behind one adapter
└── utils/                   config keys and access, item rules, version safe lookups, updater
```

### The two death paths

Everything starts in `PlayerDeathListener` at `EventPriority.LOW`. It picks exactly one of two paths and never both.

```
PlayerDeathEvent (LOW)
   │
   ├─ early exits : keepInventory, The End, excluded world, creative, PvP keep-inventory
   │
   ├─ vanilla-drop.enabled = true ─────► LockedDropService.handlePlayerDeath()
   │                                       drops taken over, respawned, reserved, stamped
   │
   └─ vanilla-drop.enabled = false ────► permissions, quotas, GraveLocationResolver.resolve()
                                           chest block, holograms, ChestData saved, drops cleared
```

The early exits run **before** the branch, so an excluded world, a creative death or a PvP keep-inventory death behaves
the same in both modes: the plugin stays out of the way.

#### Chest path

1. `GraveLocationResolver.resolve()` returns the position, or `null` when no position is acceptable.
2. The inventory is sanitized: Curse of Vanishing, excluded items, durability loss, XP.
3. `ChestIntegrityService.beginDeath()` stamps the death (see below) and the `ChestData` is written durably.
4. Only once the row is on disk are the event drops cleared. A failed write rolls the generation back and lets vanilla
   drop the items, because clearing them first would destroy them.

#### Vanilla drop path

`drops/LockedDropService` owns it end to end:

1. `takeOverDrops()` removes the stacks from the death event, except `filters.ignored-items` which stay vanilla.
2. A sequence is allocated and stamped on the player (crash protection) before the items leave the inventory.
3. Each stack is respawned with `dropItemNaturally()` at a position clamped to the world bounds, then tagged.
4. A spawn failure hands the remaining stacks back to `event.getDrops()`, never destroys them.

A reserved drop carries its state **on the item entity**, in the persistent data container:

| Tag                       | Meaning                                                  |
|---------------------------|----------------------------------------------------------|
| `locked-drop-owner`       | who may pick it up                                       |
| `locked-drop-created`     | epoch ms of the death, groups the drops of one death     |
| `locked-drop-expiration`  | epoch ms of removal, `0` = never                         |
| `locked-drop-sequence`    | integrity sequence of the death                          |
| `locked-drop-confirmed`   | `0` while the death is not proven saved                  |

That is the reason a lock survives a chunk unload and a restart: the chunk file carries it. Servers older than 1.14
have no entity persistent data, so `NoOpDropTagStorage` is selected and the lock falls back to in-memory metadata,
which is lost on restart. The in-memory `trackedDrops` map is only an index for the maintenance pass, never the source
of truth.

Enforcement has two layers, and the order matters.

The first one is written on the item entity, where the server enforces it with no plugin involved:
`LockedDropService.applyNativeLock()` sets the vanilla owner field, so Minecraft refuses to hand the drop to anybody
else, and `refreshDespawnTimer()` marks the entity as living forever so the vanilla despawn timer can never fire. That
timer is `item-despawn-rate` in `spigot.yml`, which servers routinely lower below the configured lifetime, so racing it
with a periodic age reset was never safe. It is re-applied on every maintenance pass, on chunk load and after a restart, and it is dropped as soon as
`vanilla-drop.owner-only-pickup` is turned off. **A protection that only exists in a listener is not a protection**: the
lock lived in `LockedDropListener` alone until 4.30.0 and any plugin listening later could un-cancel it.

The second one is `LockedDropListener`, which covers what the entity fields do not: hopper pickup, mobs, merges between
two different deaths, and the despawn of a drop the lifetime flag could not be set on. Its four handlers run at `EventPriority.HIGHEST` so nothing can undo
them afterwards. Keep them there.

The two layers interact in one place: `deadchest.dropPass` and `deadchest.chestPass`. The server applies the owner field
**after** the pickup event, so letting the event through is not enough for a bypass holder. The listener calls
`releaseNativeLock()` for them and the maintenance pass puts the lock back on the next second.

Shutdown undoes the lifetime flag (`releaseDespawnProtection()`), because a drop marked as living forever would stay on
the ground for good if DeadChest is removed before the server comes back. Startup marks them again.

`LockedDropEntitiesListener` handles Paper's `EntitiesLoadEvent` and is registered only when that class exists, because
Paper 1.17+ loads entities separately from their chunk.

### Crash duplication protection

`integrity/` solves one specific failure: the plugin writes its own store at death, the server writes
`playerdata/<uuid>.dat` later. A process killed in between (OOM kill, `kill -9`, host failure) freezes that
disagreement and the player reconnects with the items **and** a copy in a chest or on the ground.

The handshake:

1. **Death** allocates a monotonic sequence per player, stamped inside the player persistent data container, which the
   server serializes into the same file as the inventory. The chest or the drops record the same number.
2. **Respawn and quit** flush the player data and settle what was waiting on it.
3. **Join** compares the sequence the player file actually carries with the one recorded. A record above that number
   was created by a death the server never saved: its content is a duplicate of what the player owns again, and it is
   removed. There is no option to keep it: two copies of the same items on the server made a crash a way to duplicate on purpose.

`ChestIntegrityService.allocateSequence()` is the single allocator for both paths, because both stamp the same player
data. A drop sleeping in an unloaded chunk cannot be judged at login, so the verdict is cached per owner and applied
when its chunk comes back.

### Respawn compass

`GraveCompassService` hands out a tagged compass on respawn and retargets it on an interval.
`GraveCompassTarget` abstracts the destination: it is built either from a `ChestData` or from a `LockedDropSite`, and
`latestTarget()` keeps the newest of the two. A server switching modes therefore still points at the last death, and
the compass disappears as soon as nothing is left to walk back to.

### Scheduling and Folia

Never call `Bukkit.getScheduler()` directly. `scheduler/SchedulerAdapter` exposes two families:

- `run...` schedules through the platform scheduler (global, region or entity on Folia);
- `execute...` keeps classic synchronous semantics where possible, and routes through the right scheduler on Folia.

Anything touching a block, an entity or a chunk must go through `executeAtLocation` / `executeForEntity`, otherwise it
breaks on Folia.

Four repeating tasks are started by `DeadChestLoader.launchRepeatingTask()`: chest maintenance (1s), particle animation
(0.2s), compass retargeting (`respawn.compass-update-seconds`) and reserved drop maintenance (1s). The drop task runs
even when the mode is off, so drops created earlier still expire.

### Configuration

Adding a setting requires **three** edits, and the build will not tell you if you forget one:

1. an entry in `utils/ConfigKey` (canonical path, plus any legacy alias),
2. a `config.register(...)` call in `DeadChestLoader.registerConfig()` with the default,
3. the key in `deadchest-plugin/src/main/resources/config.yml`.

A registered key missing from the shipped file makes `detectMissingConfigs()` true on every startup, so the plugin
rewrites the config forever. `DeadChestConfig.updateConfig()` performs the migration: it backs the file up as
`config.legacy.yml`, regenerates the template, then copies every known value back. Note that `/dc reload` does **not**
migrate, it only re-reads: new keys appear on a restart.

### Localization

`localization/<lang>.json` files, `en.json` being the reference. Missing keys in another language fall back to English
at load time, so shipping a key only in `en.json` is safe but leaves other languages in English.

### Version compatibility

The plugin targets `api-version 1.13` while running on much newer servers, so Minecraft renames are a real concern:

- particles and sounds are resolved by name with fallbacks in `ClickListener`;
- materials and the Curse of Vanishing go through `utils/RegistryCompat`;
- `placement/GraveBlocks` resolves world height bounds defensively.

Rule of thumb: **do not reference a constant that may not exist on every supported version**. Resolve it by name and
skip the feature when it is missing. The same applies to types: `InventoryView` is a class on old servers and an
interface on recent ones, which is why the inventories are read from the event instead.

### Testing

MockBukkit plus Mockito, everything in `deadchest-core/src/test`. 274 tests today.

Patterns used everywhere:

- statics of `DeadChestLoader` (`plugin`, `config`, `local`, `log`) are assigned in `@BeforeEach`;
- `DeadChestConfig` is a Mockito mock, one `when(...)` per key the code under test reads;
- `MockBukkit.unmock()` and the service `clearTracking()` / `resetSessionState()` helpers run in `@AfterEach`.

MockBukkit limits worth knowing:

- **`UnimplementedOperationException` extends `TestAbortedException`.** A test that touches an unimplemented mock method
  is reported as *skipped*, not failed, so it silently stops being coverage. Watch the skip count, not just the failure
  count: `./gradlew :deadchest-core:test` then read `build/test-results/test/TEST-*.xml`. Seven skips are expected today,
  all in the chest integrity and give-back paths.
- `Entity#setTicksLived` and the whole `Item` owner and lifetime API (`setOwner`, `setUnlimitedLifetime`,
  `setCanMobPickup`) are unimplemented. Production code catches that, which means an assertion on those calls would abort
  instead of proving anything. Assert on a Mockito `mock(Item.class)`, or wrap the entity in a spy that implements them —
  `LockedDropListenerTest#withEntityApi` does the second, and the spy shares the persistent data container of the entity
  it wraps so the item left in the world stays tagged.
- A chunk is only "loaded" after an explicit `world.loadChunk(x, z)`, which matters for anything walking tracked drops.

### CI and release

`.github/workflows/ci.yml` builds, runs the tests, then uploads the shaded jar and the test reports as artifacts.
`docs.yml` publishes this documentation from `master`. The version lives in the root `build.gradle.kts` and is injected
into `plugin.yml` at build time.

### Recent additions

| Version | What                                                                                                                                |
|---------|-------------------------------------------------------------------------------------------------------------------------------------|
| 4.29.0  | Placement chain (`placement/`), respawn compass (`compass/`), crash protection (`integrity/`), replace-oldest, runtime effect lookups |
| 4.30.0  | Vanilla drop mode (`drops/`), compass and crash protection extended to it, registry safe lookups (`utils/RegistryCompat`)            |

### Known gaps

- `/dc list` does not report reserved drops, only chests.
- Reserved drops are not in the database, so there is no startup report of unsettled drops and a rollback verdict is
  only applied when the owner logs in and the chunk is loaded.
- Non English locales are missing a few keys added recently and fall back to English.
- `api-version` is still `1.13`; raising it would drop the old servers the compatibility work exists for.
