## DeadChest - Troubleshooting

This page covers common issues and quick fixes.

### Ghost/leftover hologram

Use:

```text
/dc repair
```

If needed, use the force variant:

```text
/dc repair force
```

You need admin permission (`deadchest.admin`) for repair commands.

### Items duplicated after a server crash

A server killed without a clean shutdown (out of memory kill, `kill -9`, host
failure) can roll the vanilla player file back to before a death while the
DeadChest is already stored: the player reconnects with the items still in the
inventory and finds a chest holding the same items.

DeadChest detects this on the next login of the player and removes the duplicated
chest. Nothing has to be done manually, but the console reports it:

```text
[DeadChest] Deadchest [Steve] at world X:120 Y:64 Z:-45 removed : the death was never
saved on the player side (server crash), its content is already back in the inventory of Steve.
```

Chests waiting for that decision are locked, so a player may briefly be told the
chest is being checked. They unlock as soon as their owner reconnects. If the
owner never comes back, the chest stays locked and can be removed with
`/dc remove <player>`.

Related settings live under `integrity` in
[Configuration](configuration.md#integrity-crash-duplication-protection). Keeping
`integrity.flush-player-data` enabled is what closes the duplication window
instead of only reporting it.

### DeadChest not behaving as expected after update

1. Check your server version and Java version match plugin requirements.
2. Run `/dc reload` after config changes.
3. Verify `config.yml` was migrated correctly to schema version `2`.
4. Check startup logs for localization/config parsing warnings.

!!! note
    Avoid deleting plugin data unless you know the impact on active chests.

### Commands not working

- Ensure command is `/dc ...`
- Verify required permissions for your user/group
- Check `permissions.require-*` settings in config

### WorldGuard behavior is incorrect

- Ensure `integrations.worldguard.enabled: true`
- Verify region flags (`dc-owner`, `dc-member`, `dc-guest`)
- Check `integrations.worldguard.default-allow`

### Need more help

- Share startup logs and relevant config sections when asking for support.
- Join Discord support from the home page links.

