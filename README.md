<h1  align="center">
    <img src="deadchest-logo.png" alt="Deadchest" width="800" /><br>
</h1>

<h2  align="center">
    <img src="https://cf.way2muchnoise.eu/full_322882_downloads.svg" alt="Downloads"/> 
    <a href="https://modrinth.com/plugin/dead-chest"><img src="https://img.shields.io/modrinth/dt/dead-chest?logo=modrinth&label=Modrinth%20downloads" alt="Modrinth Downloads"/></a>
    <img src="https://img.shields.io/github/last-commit/apavarino/deadchest" alt="commit"/>
    <a href="https://github.com/apavarino/deadchest/actions/workflows/ci.yml"><img src="https://github.com/apavarino/deadchest/actions/workflows/ci.yml/badge.svg" alt="CI"/></a>
    <a href="https://stellionix.github.io/Deadchest/"><img src="https://img.shields.io/badge/docs-online-blue" alt="Docs"/></a>
    <img src="https://img.shields.io/github/license/apavarino/deadchest" alt="License"/>
</h2>

DeadChest keeps a player's inventory in a chest when they die.

It is designed for Minecraft Java Edition servers and primarily targets Bukkit, Spigot, and Paper.

## Features

- Store a player's inventory in a chest on death instead of dropping items on the ground
- Or keep vanilla death drops with `vanilla-drop`, reserved to the dead player and held back from the despawn timer
- Run both modes on one server: `vanilla-drop.worlds` takes a world name or a whole dimension, and a world left out
  falls back to the normal chest behavior
- Keep the inventory on PvP deaths (`pvp.keep-inventory-on-player-kill`), scoped the same way, so the end can stay at
  full stakes while the rest of the map is forgiving
- Support modern Bukkit-based server software including Paper, Spigot, and Purpur
- Provide admin tools to inspect, remove, repair, and give back DeadChests
- Offer configurable behavior, localization, and optional integrations such as WorldGuard
- Protect against item duplication caused by a server crash, for chests and for reserved drops
- Point a respawn compass at the latest death, whichever mode is in use
- Include an API and automated tests for safer maintenance and extensions

## Compatibility

- Minecraft Java Edition
- Bukkit API `1.13+`
- Supported server software: Bukkit, Spigot, Paper, Purpur, Tuinity, and similar forks

## Download

- [Modrinth](https://modrinth.com/plugin/dead-chest)
- [CurseForge](https://www.curseforge.com/minecraft/bukkit-plugins/dead-chest)
- [Bukkit](https://dev.bukkit.org/projects/dead-chest)

## Quick Start

1. Download the latest DeadChest JAR.
2. Place it in your server's `plugins/` directory.
3. Start the server and confirm that DeadChest is enabled in the console.
4. Adjust the generated configuration files if needed.

Expected startup log:

```text
[DeadChest] Enabling DeadChest vX.X.X
```

## Documentation

The official documentation is available at [stellionix.github.io/Deadchest](https://stellionix.github.io/Deadchest/).

Useful pages:

- [Installation](https://stellionix.github.io/Deadchest/latest/installation/)
- [Configuration](https://stellionix.github.io/Deadchest/latest/configuration/)
- [Commands and Permissions](https://stellionix.github.io/Deadchest/latest/commands-and-perms/)
- [Troubleshooting](https://stellionix.github.io/Deadchest/latest/troubleshooting/)

## Statistics

<img align="center" src="https://bstats.org/signatures/bukkit/Deadchest.svg" alt="stats"/> 

More statistics are available on [bStats](https://bstats.org/plugin/bukkit/DeadChest/11385).

## Commands

The main command is `/dc`.

Common examples:

- `/dc reload`
- `/dc list`
- `/dc remove`
- `/dc giveback <player>`

The full command list and permission nodes are
documented [here](https://stellionix.github.io/Deadchest/latest/commands-and-perms/).

## Contributing

Development and contribution guidelines are available in [CONTRIBUTING.md](CONTRIBUTING.md).

New to the codebase? The [Architecture](https://stellionix.github.io/Deadchest/latest/architecture/) page
([source](docs/architecture.md)) describes the module split, the two death paths, the crash protection handshake and the
invariants to preserve when changing things.
