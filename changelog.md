## Deadchest 4.30.0 - 2026-07-30

- Added a vanilla drop mode (`vanilla-drop.enabled`) that disables DeadChest generation and keeps vanilla death drops
- Reserved the vanilla drops to the player who died (`vanilla-drop.owner-only-pickup`), including against mobs and hoppers
- Added a real time lifetime for the reserved drops (`vanilla-drop.despawn-seconds`, default 5 minutes) that keeps counting while chunks are unloaded
- Added despawn protection so reserved drops never disappear before their configured lifetime (`vanilla-drop.protect-from-despawn`)
- Added optional invulnerability and glowing outline for reserved drops (`vanilla-drop.invulnerable`, `vanilla-drop.glow`)
- Added the `deadchest.dropPass` permission to bypass reserved drops
- Kept the death coordinates message in vanilla drop mode
- The respawn compass now works in vanilla drop mode: with no chest to target it points at the place where the reserved drops
  are waiting, follows the newest death, and is removed once every drop has been picked up or expired
- Extended the crash duplication protection to the vanilla drop mode. The death is stamped on the player data before the items
  leave the inventory, the reserved drops carry the same sequence in their persistent tags, and the next login of the owner
  decides: the drops are confirmed when the player file kept the death, and removed as duplicates when a crash rolled it back
  while the items came back in the inventory. `integrity.crash-protection`, `integrity.flush-player-data` and
  `integrity.on-rollback` drive it exactly like they do for chests, and both sides now share one sequence allocator
- Fixed the vanilla drop mode lock, which relied entirely on DeadChest winning the pickup event. The reservation is now
  written on the item entity itself (`Item#setOwner`), where the server enforces it without any plugin involved, and it is
  restored on every maintenance pass, on chunk load and after a restart. The pickup, despawn and merge handlers also moved
  from `LOW` to `HIGHEST` priority, so a plugin listening later can no longer un-cancel them and quietly open the drops to
  everybody. A player holding `deadchest.dropPass` or `deadchest.chestPass` still gets through: the native lock is lifted for
  their pickup and put back afterwards
- Fixed reserved drops disappearing before their configured lifetime on any server that lowers `item-despawn-rate` in
  spigot.yml. The plugin used to reset the age of each drop once per second and cancel the despawn event, which races a
  timer it does not control: a server at `3000` took the drops away after 2 minutes 30 while `vanilla-drop.despawn-seconds`
  announced 5 minutes. Reserved drops are now marked as living forever on the entity, so `despawn-seconds` is the only
  thing that removes them. Shutdown hands them back to the vanilla timer so removing DeadChest cannot leave items that
  never disappear
- Fixed `vanilla-drop.protect-from-despawn: false` being ignored: the despawn handler cancelled the vanilla 5 minutes timer
  even when the protection was turned off, so drops outlived the lifetime the option documents
- Fixed `pvp.keep-inventory-on-player-kill` treating a self inflicted death as a player kill. A player killed by their own
  TNT, their own projectile or a `/kill` on themselves is reported by the server as their own killer, so any player could
  keep their inventory on demand while the option was on. Only a death caused by somebody else counts as PvP now
- Fixed mobs being blocked from reserved drops even with `vanilla-drop.owner-only-pickup: false`, which documents that
  everybody can take them and which the hopper path already honored
- Fixed the ignore-list GUI reading `InventoryView`, which is a class on the supported old servers and an interface on the
  recent ones, so a click threw `IncompatibleClassChangeError` on one of the two. The inventories are now read from the event.
- The Curse of Vanishing is now detected by enchantment key instead of the `Enchantment.VANISHING_CURSE` constant, which
  moved to a registry
- The barrel, shulker box and ender chest grave blocks are now resolved by name and fall back to a chest, instead of
  constants that do not exist on every supported version

## Deadchest 4.29.0 - 2026-07-25

- Added a respawn compass pointing at the latest DeadChest (`respawn.compass`). It cannot be dropped, cannot be moved into a
  container, is never stored in a DeadChest, is retargeted every `respawn.compass-update-seconds`, and disappears once every
  chest has been collected
- Added `chest.replace-oldest` to replace the oldest DeadChest instead of creating none when a player reaches the limit
- Reworked where a DeadChest is placed into a single resolution chain (`generation.placement.*`), so the options never conflict:
  the death context selects one rule (void, lava, water, powder snow, suffocation, free fall), then the position is validated
  against the world border, the build height, the blocks already used and the protection plugins
- Added `generation.placement.safe-location` to only create DeadChests where the owner may build, asking protection plugins the
  same way a real block placement would
- Added void, lava-top, lava-smart, water-top, water-bottom, suffocation, powder-snow and ground placement rules
- Fixed two entities dying on the same block sharing one position: the second DeadChest now moves to the closest free block
- Fixed a DeadChest refused by the world border or by the build height: it is now placed below the death position instead of
  not being created
- Fixed the item duplication caused by a server killed without a clean shutdown (out of memory kill, `kill -9`, host crash).
  The plugin database was written at death while the vanilla player file was not, so the player came back with the items still
  in the inventory AND a deadchest holding a copy of them. Deaths and chest recoveries are now stamped on both sides and only
  completed once the player data reached the disk, and a transfer left half done by a crash is settled at the next login.
- Added `integrity.crash-protection`, `integrity.flush-player-data` and `integrity.on-rollback` to configure that behavior
- Fixed items being destroyed when a deadchest could not be stored: the generation is now rolled back and vanilla drops apply
- Fixed items being duplicated when a chest expired or was given back while the database write was still pending
- Fixed a chest content being handed over twice by two interactions on the same chest
- Fixed two deadchests sharing the same block, which left an unreachable row behind and could delete the wrong chest
- Fixed deletions targeting a chest by position, which could remove a different chest stored on the same block
- Database failures are now logged instead of being silently swallowed, and committed writes are flushed to disk

- Fixed the storage initialization stopping halfway: the schema migration closed the shared connection, so the location and
  death id indexes were never created and the legacy `chestData.yml` migration callback never ran
- Fixed an explosion destroying a DeadChest placed right next to another one, the protection loop skipped a block every time
  it protected one
- Fixed a piston pushing a line of heads only protecting the DeadChest when it came first
- Fixed `/dc` tab completion offering `remove` to players who may only list, and `list` to players who may only remove
- Removed dead code: unused chest accessors, an unused config accessor that parsed a `config.yml` from the server root on
  every startup, and the height helper left behind by the placement rework
- The CI workflow now builds the shaded plugin jar and uploads it, together with the test reports, as build artifacts
- Fixed the placement chain referencing the `FREEZE` damage cause directly, which does not exist before Minecraft 1.17 and
  threw on the first death of any older server. It is now resolved by name.
- The respawn compass is no longer handed out on servers without persistent item data (before Minecraft 1.14), where it could
  not be recognized and would have been dropped or stored like a normal item
- Pickup particle and sound are now resolved by name with fallbacks instead of compiled constants. Minecraft renames them
  between versions (TOTEM became TOTEM_OF_UNDYING, FIREWORKS_SPARK became FIREWORK), and the old fallback would have thrown on
  a version where its constant no longer exists. When nothing matches, the effect is skipped instead of breaking the pickup.

## Deadchest 4.28.0 - 2026-03-21

- Added a two-phase loot system with a private phase (`chest.duration-seconds`) and an optional public loot phase (`chest.loot.*`)
- Added configurable public-phase access rules for owner, killer, and other players
- Added public-phase expiration handling with dedicated timeout behavior
- Added a dedicated hologram status line above the owner name
- Added Minecraft-friendly hologram states: `PRIVATE`, `OPEN`, `PUBLIC`, `KILLER`, `OWNER`, and `SHARE`

## Deadchest 4.27.0 - 2026-03-16

- Fixed log spam on deadchest expiration

## Deadchest 4.26.0 - 2026-03-14

- Added Folia support !
- Reworked ignored items to feel more consistent between the config and the in-game `/dc ignore` menu.
- Improved support for custom items, making advanced setups easier to manage.

## Deadchest 4.25.0 - 2026-03-10

- YAML Configuration Overhaul
The config.yml structure has been fully reorganized for better clarity and easier maintenance.
Settings are now grouped into clear sections (localization, visuals, sound, gameplay, permissions, etc.).
This makes customization faster and reduces configuration mistakes.

- Localization System Upgrade
The localization system has been redesigned to be cleaner and more consistent across the plugin.
Built-in languages: en, fr, es, de, it, pl, pt-br, zh-cn.
Migration from legacy localization/config systems is handled automatically, so existing setups are updated without manual conversion.

- DeadChest Visual Customization
Added configurable standing chest effects (style, radius, speed, enable/disable).
Added configurable pickup animation (particle type, count, spread, speed, vertical offset).
Added configurable pickup sound (enable/disable, sound name, volume, pitch).

## Deadchest 4.24.0 - 2026-02-28

- Fixed SQLite persistence for DeadChests to prevent some incomplete saves/updates.
- Fixed updates of existing DeadChests for more reliable data consistency.
- Fixed batch save/remove behavior to avoid database inconsistencies.
- Improved startup data recovery so DeadChests reload more reliably after server restart.

- Fixed and improved WorldGuard integration (more consistent owner/member/guest flag handling).
- Added robustness improvements to avoid certain crashes when data is missing.

- Internal cleanup and consistency improvements in the DeadChest persistence flow.


## Deadchest 4.23.0 - 2025-09-15

- Performance & stability update
- All chests are stored in a dedicated SQLite database instead of a large `ChestData.yml` file
- Data processing runs on a separate thread, reducing lag spikes
- Existing `ChestData.yml` data is migrated automatically on update
- In case of migration issues, you can stay on `v4.22.2` or run `/dc removeall` before switching to `v4.23.0`

## Deadchest 4.22.2 - 2025-08-20

- Improved code quality and overall performance
- Fixed an issue where a player carrying only ignored items would still generate an empty DeadChest
- Corrected plugin name display: restored from Deadchest back to DeadChest

## Deadchest 4.22.1 - 2025-08-13

- Improved death handling in lava
- Minor performance optimizations
- Fixed console error when opening inventory on Minecraft versions below 1.21

## Deadchest 4.22.0 - 2025-08-10


## Deadchest 4.21.1 - 2024-07-04


## Deadchest 4.21.0 - 2024-05-29


## Deadchest 4.20.0 - 2024-04-14


## Deadchest 4.19.1 - 2024-03-28


## Deadchest 4.19.0 - 2024-02-22


## Deadchest 4.18.0 - 2024-02-01

- Added the `item-durability-loss-on-death` option to apply durability loss when a player dies
- Optimized the plugin file size to be lighter

## Deadchest 4.17.0 - 2023-10-29


## Deadchest 4.16.1 - 2023-07-23


## Deadchest 4.16.0 - 2023-05-10


## Deadchest 4.15.0 - 2023-03-21


## Deadchest 4.14.0 - 2023-02-04


## Deadchest 4.13.0 - 2022-11-22


## Deadchest 4.12.1 - 2022-09-22


## Deadchest 4.12.0 - 2022-09-17

- Fixing Deadchest not working on restart

## Deadchest 4.11.0 - 2022-09-03

- Adding auto-updater
- Code change : Update configuration code
- Code change : Update commands system
- Fix typo "Indestuctible" in config.yml
- Add force option to /dc repair
- Potentially fixing chunk keeping loading for no reason

## Deadchest 4.10.0 - 2022-06-21

- Fixing all performance issue

## Deadchest 4.9.0

- Improving memory performance related to chunk loading

## Deadchest 4.8.2

- Fixed performance issue

## Deadchest 4.8.1

- Fixed spam error on the console

## Deadchest 4.8.0

BUGFIX :

- Fixed hologram issue staying in the world on 1.17- version on unload chunk
- Fixed unsafe head as deadchest (head are now not destroyable anymore by water/lava/piston..)
- Fixed Netherite boots in place of chestplate
- Fixed min height for Deadchest. Plugin now handle deadchest under y 0

CHANGE :

- You can no longer use right click to get your chest
- If chest is abnormally removed. (like world bug, cuboid etc..), it now respawn at the same place until the timer is
  out

## Deadchest 4.7.0

FEATURES :

- Add gamerules keepInventory support (finally !) : If enable no Deadchest is generate
- Code optimization
- Switch default Deadchest time from 5min to 15min
- Add support for customs items (Slimefun/Minetinker etc...)
- Better 1.17 support
- API Update
- Add removeChest() method
- Add DeadchesPickUpEvent

BUG FIX :

- Fix : error on /dc list when world does not exists anymore
- Fix : Expired Deadchests was not removed on unloaded chunck

## Deadchest 4.6.0

**Features**

- Official support for 1.17
- Added basic API
- Added permission to manage if user can open Deadchest
- Added 3 new Deadchest type !
  - Barrel chest (dropBlock : 3 in config.yml)
  - Shulker chest  (dropBlock : 4 in config.yml)
  - Ender chest  (dropBlock : 5 in config.yml)

**BugFix**

- Fix netherite stuff error with Minecraft lower than 1.16

## Deadchest 4.5.1 - 2021-05-21

- Fix saving issue in particular cases

## Deadchest 4.5.0

**FEATURES**

- Add option to disable Deadchest in lava
- Add option to disable Deadchest in water
- Add option to disable Deadchest on rails
- Add option to disable Deadchest on minecart
- Added bstats for plugin statistics

**BUGFIX**

- Fix player head destroying with water issue
- Fix Task exception spamming on console in certain cases

## Deadchest 4.4.0

**FEATURES**

- **Added player head as deadchest** ! (configurable in config.yml)
- Added possibility to change [Deadchest] prefix by something else on command feedback
- Added auto update configuration file system when updating the plugin

**BUGFIX**

- Fix Task exception spamming on console in certain cases

## Deadchest 4.3.0

**FEATURES**
- Update for Minecraft 1.16.4
- Upgrade Worldguard support
  - Deadchest now handle region priority
  - remove dc_nobody flag
  - add dc_guest flag

**BUGFIX**
- Invisible armorstand was not usable with Deadchest
- Activation of worldguard detection was not working correctly

## Deadchest 4.2.0

**FEATURES**
- Added option to exclude items from Deadchest in config.yml

**BUGFIX**
- Fix issue with CRIMSON_DOOR on 1.15 and lower
- Fix exception with reload metadata

## Deadchest 4.1.1

Hotfix : Patch the issue related to WorldGuard on 4.1.0.

## Deadchest 4.1.0

**FEATURES**
- Option to enable/disable Worldguard check for deadchest generation
- Add Turtle Helmet on auto-equip
- Add Netherite stuff on auto-equip

**CHANGE**
- Remove previous Worldguard support system
- New WorldGuard support : Works now with flags :
  - **dc-owner** :  Only owner of the region can generate deadchest (true/false)
  - **dc-member** : Only member of the region can generate deadchest (true/false)
  - **dc-nobody** : Nobody can generate deadchest in the region
- When a player dies on ladder, inside a door or in vines, deadchest now try to place the deadchest next to it instead of placing it at the top

**BUGFIX**
- **Fix Deadchest dupe with books**
- Sound of getting chest was heard by everyone

## Deadchest 4.0.0

**FEATURES**
- Official support for 1.16.X
- Adding colors and styling for holograms and texts
- Adding new localization system with powerfull configuration
- Adding timer customization
- Adding Log system : All events related to deadchest are now stocked in a file
- Adding WorldGuard support : A Deadchest is not generate if the player is not member/owner of the region
  where he died
- Adding autocompletion for commands
- Adding option in config file to enable items dropping on the floor when a deadchest time out

**CHANGES**
- Code refactoring
- Improve stability
- No more collision with holograms. That mean that you can get your deadchest by the top or the bottom of it without hitting the hologram instead.
- Remove "×" at the beginning and the end of holograms
- Improve /dc repair command feedback
- New system to handle deadchest holograms
- Improve comments of local config file

**BUGFIX**
- Deadchest is now generated correctly on GRASS_PATH and FARMLAND
- Fixed typo issue : infinate -> infinite

## Deadchest 3.5.0

- Increase the performance of the plugin by decreasing a lot the memory use. The performances will be especially notable for servers which has a lot of players.

## Deadchest 3.4.0

- Add option to disable/enable message with deadchest position on death

## Deadchest 3.3.0

Fix hologram that stay after getting deadchest (this time it’s the right one.)
Add missing translation
Upgrade translation system
Generate deadchest when player dies upper than map max height
Item in armors slots with Curse of Vanishing was not removing of deadchest
Auto-equip armor no longer equip item with curse of binding

## Deadchest 3.2.0

FEATURES 
- Message on death to give location of the deadchest
- Items with Curse of Vanashing are no longer stored in deadchests
- Added option to disable deadchest in creative mode
- Added option to choose how deadchest drop items (inventory or ground)
- Added new permission deadchest.giveBack (op by default)
- Added new command /dc giveback <PlayerName> to get back the content of the oldest deadhcest of a player to him.
  If you want to recover several deadchest for a player, you juste have to execute the command again.

CHANGES 
- Update localisation system
- Add more localisation
- Change permission : deadchest.infinyChest by deadchest.infinityChest
- deadchest.ChestPass permission in now enable by default for admin

BUGFIX 
- "Excluded world" config option was not generated on config.yml on plugin loading
- Corrupted Deadchest when dying top of a world
- DeadChests can be merge with normal chest
- Correction typo infiny --> infinity
- No feedback when player type /dc list all if there is no deadchest
- No feedback when player type /dc list (playerName) if there is no deadchest
- Location was not updated during a /dc reload

## Deadchest 3.1.0

- Plugin now manage death out of the world ( finally ! )
- Stuff go now directly to the inventory instead of be dropping on the ground (except if inventory is full)
- Smart auto equip system for armors and elytra on opening deadchest
- Add help section /help dc
- Add world name on/dc list
- [BUGFIX] Hologram can be equip with stuff (fun but useless)
- [BUGFIX] Bed explosion in nether and end break deadchests
- [BUGFIX] Corrupted time with infiny chest when using /dc list ,
- [BUGFIX] Remove /dclist
- [BUGFIX] Add feedback for command /dc

## Deadchest 3.0.0

New features :
- Add option to DeadChestDuration. 0 = infiny chest duration
- Add option to maxDeadChestPerPlayer. 0 = infiny chests
- Add command /dc removeinfinate to remove all infiny chest (deadchest.admin)
- Add command /dc removeall to remove all deadchests (deadchest.admin)
- Add command /dc remove <Player> all deadchests of a player (deadchest.remove.other)
- Add command /dc remove to remove all deadchest of the current player (deadchest.remove.own)
- Add permission deadchest.remove.own
- Add option RequirePermissionToGenerate to choose if players need permission to use DeadChest
- Add option RequirePermissionToListOwn to choose if players need permission to list their dead chests
- Add permission deadchest.list.own for /dc list
- Add option AutoCleanupOnStart to remove all existing deadchests on startup
- Add command /dc list <all/Player> to display deadchests of all or a specific player (deadchest.list.other)
- Add a new config file (locale.yml) . You can edit text of the plugin to the langage you want
- New option to disable DeadChest on certain worlds in config.yml
  Change :
- Massive code rewrite
- Performance optimization
- deadchest.keepInventory permission change to deadchest.generate
- /dcinfo change to /dc list
- Upgrade of config.yml file to be more friendly

## Deadchest 2.8.0

- Upgrade save system to handle worlds that are not currently running
- Patch NullPointerException for Task
- Patch issue with Multiverse
- Minor fix

## Deadchest 2.7.0

- Upgrade save system to handle worlds that are not currently running
- Patch NullPointerException for Task
- Patch issue with Multiverse
- Minor fix

## Deadchest 2.6.0

- Fix bad chest position when player dies in cave

## Deadchest 2.4.0

- Add compatibility with NPC
- Add command /dcinfo to view the position of your dead chests and the remaining time ! (need deadchest.info permission)

## Deadchest 2.3.0

BUGFIX : Holographic display was staying when a deadchest is removed (This is the third time I try to remove this damn bug, I hope this time it will works fine for everyone !)
BUGFIX : Remaining time is corrupted on deadchest in certain cases
BUGFIX : Holographic bug when two dead chest are near
BUGFIX : Attempt to get full compatibility with NPC plugins

## Deadchest 2.2.0

- Patch nether issue
- Patch incorrect location of deadchest in certain cases when player dies

## Deadchest 2.1.0
- Change permission deadchest.keepInventory to true by default.

## Deadchest 2.0.0

FEATURES :
- Add parameter maxDeadChestPerPlayer, corresponding to the maximum number of deadchest that a player can have. if this
  number is exceeded. Inventory is dropped on the floor.
- Add permission deadchest.admin (need to type commands)
- Add command /dc reload to reload the plugin
- Add command /dc repair to clear holographic display on chest if something went wrong

MINOR FEATURES :
- Add sound when player open deadchest
- Add effect when player open deadchest

CHANGE :
- Massive code rewrite
- Performance optimization
- Improved stability
- Plugin configuration and deadchest data are now separate in two different files
- Remove permission deadChest.noDropChest
- Permission deadChest.keepInventory is now disable by default
- Removing parameter EnableForOP
- Removing some useless logs on enable and disable

BUGFIX :
- Players with Essential plugin was keeping their inventory on death allowing duplication inventory.
- Deadchest was generate even player inventory is empty
- In some case, ClassCastException error was occured
- If two deadchest was near, that removed some holographic display on chest
- Infiny chest had a corrupted left time
- Holographic display was not removing if the deadchest was destroyed
- Deadchest was not removed in memory if destroyed
- If player was dying in a wall, deadchest was replacing the wall block

## Deadchest 1.7.0

- FIX ISSUE : Duplication item in strange condition
- FIX ISSUE : If a player dies on a semi-block like campfire, the player deadchest appear on this block and destroy it.
- FIX ISSUE : On restarting server dead chest become inaccessible for owner

## Deadchest 1.5.0

- Patch error spamming console

## Deadchest 1.4.0

- Performance optimization
- Increase response time by x20
- Improve placement of holographic display
- Patch issue with offline player and indestructible chest
- Patch issue with saving date of dead chest
- Patch issue with updating data on disable
- Patch issue when player disconnect he can't get back his own chest
- Add holographic timer before the chest disappear

## Deadchest 1.3.0

- BUGFIX : Comments on config file disappear after reload
- ADD OPTION : EnableForOP in config file. Disable or not deadChest for OP
- ADD PERMISSIONS :
  - deadchest.noDropChest : player don't drop dead chest on death
  - deadchest.chestPass : Player can open all deadChest
  - deadchest.infinyChest : Player dead chest never disappaear

## Deadchest 1.2.0

- Add permission deadchest.keepInventory enable by default for all.
- Update config file description

## Deadchest 1.1.0

- Change default deadChestDuration to 600
- BUFIX : config file reset on reload
- Optimization
- Add header with some explanation on config.yml file

## Deadchest 1.0.0

- Initial version
