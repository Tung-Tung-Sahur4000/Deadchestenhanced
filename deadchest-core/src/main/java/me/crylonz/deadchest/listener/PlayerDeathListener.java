package me.crylonz.deadchest.listener;

import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.Permission;
import me.crylonz.deadchest.db.ChestDataRepository;
import me.crylonz.deadchest.integrity.ChestIntegrityService;
import me.crylonz.deadchest.placement.GraveLocationResolver;
import me.crylonz.deadchest.drops.LockedDropService;
import me.crylonz.deadchest.utils.ConfigKey;
import me.crylonz.deadchest.utils.RegistryCompat;
import me.crylonz.deadchest.utils.Utils;
import me.crylonz.deadchest.utils.WorldScope;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static me.crylonz.deadchest.DeadChestLoader.*;
import static me.crylonz.deadchest.DeadChestManager.playerDeadChestAmount;
import static me.crylonz.deadchest.DeadChestManager.replaceOldestChest;
import static me.crylonz.deadchest.utils.ConfigKey.GENERATE_DEADCHEST_IN_CREATIVE;
import static me.crylonz.deadchest.utils.ConfigKey.KEEP_INVENTORY_ON_PVP_DEATH;
import static me.crylonz.deadchest.utils.ExpUtils.getTotalExperienceToStore;
import static me.crylonz.deadchest.utils.Utils.*;

public class PlayerDeathListener implements Listener {

    // Keep array order explicit when used
    private static final int HOLO_TIME = 0;
    private static final int HOLO_STATUS = 1;
    private static final int HOLO_NAME = 2;

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerDeathEvent(PlayerDeathEvent event) {

        // 1) Early exits
        if (keepInventoryAlreadyOn(event)) return;

        final Player player = event.getEntity().getPlayer();
        if (player == null) return;

        // A PvP death decides on its own what happens to the items, so it is
        // answered before the checks below. Those only say where a grave may be
        // generated : dying in the end, in an excluded world or in creative turns
        // the generation off, it does not mean the items have to drop.
        if (pvpKeepInventoryCase(event, player)) return;

        // 1b) Vanilla drop mode : no chest at all, only locked drops on the ground.
        // It carries its own switch and its own world scope, so the options below,
        // which only say where a grave may be generated, no longer decide anything
        // for it. A world left out of 'vanilla-drop.worlds' falls through to the
        // normal chest behavior, which lets both modes live on the same server.
        if (LockedDropService.isVanillaDropModeEnabled()) {
            if (LockedDropService.appliesIn(player.getWorld())) {
                generateLog("Player [" + player.getName() + "] died with " + ConfigKey.VANILLA_DROP_ENABLED +
                        " set to true : items are dropped like vanilla. No Deadchest generated");
                LockedDropService.handlePlayerDeath(event, player);
                return;
            }

            generateLog("Player [" + player.getName() + "] died in a world left out of "
                    + ConfigKey.VANILLA_DROP_WORLDS + " : the normal deadchest behavior applies");
        }

        if (disallowedEndGeneration(event)) return;
        if (playerOrWorldDisallowsGeneration(player)) return;

        if (player.getInventory().isEmpty()) {
            generateLog("Player [" + player.getName() + "] died without inventory : No Deadchest generated");
            return;
        }

        // 2) Permissions & quotas
        if (!(worldGuardCheck(player) && (player.hasPermission(Permission.GENERATE.label)
                || !config.getBoolean(ConfigKey.REQUIRE_PERMISSION_TO_GENERATE))))
            return;

        if (!underPerPlayerLimit(player)) return;


        // 3) Block/Vehicle Location & Constraints
        World world = player.getWorld();
        Location location = player.getLocation();

        if (disallowedFluidOrRailOrMinecart(player, location)) return;

        // 4) Where the grave goes: one chained resolution handling the void, lava,
        // water, powder snow, suffocation and free fall, then the world border,
        // the build height, the protected regions and the blocks already taken by
        // another grave.
        final Location graveLocation = GraveLocationResolver.resolve(player, location);
        if (graveLocation == null) {
            generateLog("Player [" + player.getName() + "] died where no deadchest can be placed : No Deadchest generated");
            player.sendMessage(local.prefixed("death.not-generated"));
            return;
        }

        Block block = world.getBlockAt(graveLocation);

        // 5) Inventory cleaning (vanishing, excluded/ignored items, durability, XP)
        sanitizeInventoryOnDeath(event, player);

        // 6) Preparing items to be stored (same slots, null kept)
        ItemStack[] original = player.getInventory().getContents();
        ItemStack[] itemsToStore = prepareItemsToStore(original);

        // Check if there's at least one valid item to store
        boolean hasSomethingToStore = Arrays.stream(itemsToStore)
                .anyMatch(Objects::nonNull);

        if (!hasSomethingToStore) {
            generateLog("Player [" + player.getName() + "] died but no valid items remain to store. No Deadchest generated");
            return;
        }

        // 7) Chest type + holograms
        generateDeadChest(block, player);
        ArmorStand[] holos = createHolograms(block, event.getEntity().getDisplayName());

        // 8) Building & saving the DeadChest (ChestData), then restoring player inventory
        if (!buildAndSaveChestData(player, block, holos[HOLO_TIME], holos[HOLO_NAME], holos[HOLO_STATUS], itemsToStore)) {
            // The chest is not on disk. Clearing the inventory now would destroy
            // the items, so the generation is rolled back and vanilla keeps the
            // drops it already holds.
            rollbackFailedGeneration(block, holos);
            log.severe("[DeadChest] Deadchest of [" + player.getName() + "] could not be stored, items left to vanilla drops");
            generateLog("Deadchest of [" + player.getName() + "] could not be stored : generation cancelled, items dropped by vanilla");
            player.sendMessage(local.prefixed("death.not-generated"));
            return;
        }

        // 9) Clean up drops & remove remaining items on player side
        clearEventDropsAndPlayerInventory(event, player);

        // 10) Position message (optional), persistence & logs
        maybeSendPosition(player, block);
        persistAndLog(player, block, itemsToStore);
    }

    private boolean keepInventoryAlreadyOn(PlayerDeathEvent e) {
        if (e.getKeepInventory()) {
            generateLog("Keep Inventory is set to ON. No Deadchest generated");
            return true;
        }
        return false;
    }

    private boolean disallowedEndGeneration(PlayerDeathEvent e) {
        if (checkTheEndGeneration(e.getEntity())) {
            generateLog("Player dies in the end and " + ConfigKey.GENERATE_IN_THE_END + " is set to false. No Deadchest generated");
            return true;
        }
        return false;
    }

    private boolean playerOrWorldDisallowsGeneration(Player p) {
        if (p == null
                || config.getArray(ConfigKey.EXCLUDED_WORLDS).contains(p.getWorld().getName())
                || (!config.getBoolean(GENERATE_DEADCHEST_IN_CREATIVE)) && p.getGameMode().equals(GameMode.CREATIVE)) {
            generateLog("Player dies in an excluded world or dies in creative with " + GENERATE_DEADCHEST_IN_CREATIVE + " set to false. No Deadchest generated");
            return true;
        }
        return false;
    }

    private boolean pvpKeepInventoryCase(PlayerDeathEvent e, Player p) {
        if (config.getBoolean(KEEP_INVENTORY_ON_PVP_DEATH)) {
            final Player killer = p.getKiller();

            // A player killed by their own hand reports themselves as the killer :
            // own TNT, own projectile, or a '/kill' run on themselves. Counting that
            // as PvP would hand everybody a way to keep their inventory on demand,
            // so only a death caused by somebody else is treated as a player kill.
            if (killer != null && !killer.getUniqueId().equals(p.getUniqueId())
                    && pvpKeepInventoryAppliesIn(p.getWorld())) {
                e.setKeepInventory(true);
                e.getDrops().clear();
                generateLog("Player dies in PVP and " + KEEP_INVENTORY_ON_PVP_DEATH + " set to true. No Deadchest generated");
                return true;
            }
        }
        return false;
    }

    /**
     * Restricts the PvP keep inventory to a part of the server, so a dimension can
     * be left at full stakes while the rest of the map is forgiving.
     *
     * @param world world of the death
     * @return {@code true} when a player kill keeps the inventory in that world
     */
    private boolean pvpKeepInventoryAppliesIn(World world) {
        return WorldScope.covers(config.getArray(ConfigKey.KEEP_INVENTORY_ON_PVP_WORLDS), world);
    }

    private boolean underPerPlayerLimit(Player p) {
        if (!p.getMetadata("NPC").isEmpty()) {
            return false;
        }

        final int maxPerPlayer = config.getInt(ConfigKey.MAX_DEAD_CHEST_PER_PLAYER);
        if (maxPerPlayer == 0 || playerDeadChestAmount(p) < maxPerPlayer) {
            return true;
        }

        // Limit reached: either the death is not stored, or the oldest grave makes
        // room for the new one.
        if (!config.getBoolean(ConfigKey.REPLACE_OLDEST)) {
            generateLog("Player [" + p.getName() + "] reached " + maxPerPlayer + " deadchests : No Deadchest generated");
            return false;
        }

        while (playerDeadChestAmount(p) >= maxPerPlayer && replaceOldestChest(p)) {
            // A player over the limit after a configuration change may need more
            // than one removal to get back under it.
        }
        return playerDeadChestAmount(p) < maxPerPlayer;
    }

    private boolean disallowedFluidOrRailOrMinecart(Player p, Location loc) {
        final Block block = loc.getBlock();
        final Material blockType = block.getType();

        // --- Fluids ---
        if (!config.getBoolean(ConfigKey.GENERATE_ON_LAVA) && blockType == Material.LAVA) {
            generateLog("Player dies in lava : No deadchest generated");
            return true;
        }
        if (!config.getBoolean(ConfigKey.GENERATE_ON_WATER) && blockType == Material.WATER) {
            generateLog("Player dies in water : No deadchest generated");
            return true;
        }

        // --- Rails (apply GENERATE_ON_RAILS to ALL rail types) ---
        // Tag.RAILS includes: RAIL, POWERED_RAIL, DETECTOR_RAIL, ACTIVATOR_RAIL
        final boolean isAnyRail = Tag.RAILS.isTagged(blockType);
        final boolean allowRails = config.getBoolean(ConfigKey.GENERATE_ON_RAILS);

        if (isAnyRail && !allowRails) {
            // Keep informative logging as before
            log.warning("Block type at death: " + blockType);
            log.warning("GENERATE_ON_RAILS=" + allowRails);
            generateLog("Player dies on rails : No deadchest generated");
            return true;
        }

        // --- Minecart ---
        if (!config.getBoolean(ConfigKey.GENERATE_IN_MINECART) && p.getVehicle() != null) {
            if (p.getVehicle().getType().equals(EntityType.MINECART)) {
                generateLog("Player dies in a minecart : No deadchest generated");
                return true;
            }
        }

        return false;
    }

    private void sanitizeInventoryOnDeath(PlayerDeathEvent e, Player p) {
        // The order matters:
        // 1) remove vanishing items first,
        // 2) then remove excluded items,
        // 3) then apply durability loss on remaining,
        // 4) finally adjust XP drop if configured.
        removeVanishingItems(p.getInventory());
        removeExcludedItems(p.getInventory());
        applyDurabilityLoss(p.getInventory());
        if (config.getBoolean(ConfigKey.STORE_XP)) {
            e.setDroppedExp(0);
        }
    }

    void removeVanishingItems(PlayerInventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (hasVanishing(item)) {
                inv.remove(item);
            }
        }

        inv.setHelmet(clearIfVanishing(inv.getHelmet()));
        inv.setChestplate(clearIfVanishing(inv.getChestplate()));
        inv.setLeggings(clearIfVanishing(inv.getLeggings()));
        inv.setBoots(clearIfVanishing(inv.getBoots()));
        inv.setItemInOffHand(clearIfVanishing(inv.getItemInOffHand()));
    }

    private ItemStack clearIfVanishing(ItemStack item) {
        return hasVanishing(item) ? null : item;
    }

    private boolean hasVanishing(ItemStack item) {
        if (item == null) {
            return false;
        }

        for (Enchantment enchantment : item.getEnchantments().keySet()) {
            if (RegistryCompat.isVanishingCurse(enchantment)) {
                return true;
            }
        }

        return false;
    }

    private void removeExcludedItems(PlayerInventory inv) {
        final List<String> excluded = config.getArray(ConfigKey.EXCLUDED_ITEMS);
        for (String item : excluded) {
            if (item != null) {
                Material mat = Material.getMaterial(item.toUpperCase());
                if (mat != null) inv.remove(mat);
            }
        }
    }

    private void applyDurabilityLoss(PlayerInventory inv) {
        final int lossPct = config.getInt(ConfigKey.ITEM_DURABILITY_LOSS_ON_DEATH);
        if (lossPct <= 0) return;

        Arrays.stream(inv.getContents())
                .filter(Objects::nonNull)
                .filter(itemStack -> itemStack.getItemMeta() instanceof Damageable)
                .forEach(itemStack -> {
                    Damageable itemData = (Damageable) itemStack.getItemMeta();
                    int loss = (int) (itemStack.getType().getMaxDurability() * lossPct / 100.0);
                    int newDamage = itemData.getDamage() + loss;
                    itemData.setDamage(newDamage);

                    if (newDamage > itemStack.getType().getMaxDurability()) {
                        inv.remove(itemStack);
                    } else {
                        itemStack.setItemMeta(itemData);
                    }
                });
    }

    private ItemStack[] prepareItemsToStore(ItemStack[] playerInv) {
        ItemStack[] itemsToStore = new ItemStack[playerInv.length];
        for (int i = 0; i < playerInv.length; i++) {
            ItemStack item = playerInv[i];
            if (item != null && !isIgnoredItem(item)) {
                itemsToStore[i] = item;
            }
            // keep null to preserve slot positions
        }
        return itemsToStore;
    }

    /**
     * Builds the chest, stamps the death on the player and stores everything
     * before the caller is allowed to clear the inventory.
     *
     * @return {@code true} when the chest is durably stored
     */
    private boolean buildAndSaveChestData(Player p, Block b, ArmorStand holoTime, ArmorStand holoName, ArmorStand holoStatus, ItemStack[] itemsToStore) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] snapshot = inv.getContents();
        inv.setContents(itemsToStore);
        final ChestData chestData = cerateChestData(p, b, holoTime, holoName, holoStatus, inv);
        inv.setContents(snapshot);

        // Stamp the player before anything is written: the stamp travels inside
        // the same playerdata file as the inventory, so it is the proof that the
        // death survived on the player side too.
        ChestIntegrityService.beginDeath(p, chestData);

        // Wait for the insert. An asynchronous write here can be lost by a hard
        // kill happening in the same tick, and the inventory is cleared right
        // after this call.
        if (!ChestDataRepository.saveDurable(chestData)) {
            generateLog("Could not store deadchest of [" + p.getName() + "] in " + b.getWorld().getName() +
                    " at X:" + b.getX() + " Y:" + b.getY() + " Z:" + b.getZ());
            return false;
        }

        DeadChestLoader.getChestDataCache().addChestData(chestData);
        return true;
    }

    private static ChestData cerateChestData(final Player p, final Block b, final ArmorStand holoTime, final ArmorStand holoName, final ArmorStand holoStatus, final PlayerInventory inv) {
        final ChestData chestData = new ChestData(
                inv,
                b.getLocation(),
                p,
                p.hasPermission(Permission.INFINITY_CHEST.label),
                holoTime,
                holoName,
                getTotalExperienceToStore(p)
        );
        chestData.setHolographicStatusId(holoStatus == null ? null : holoStatus.getUniqueId());
        return chestData;
    }

    /**
     * Undoes a generation that could not be persisted, so the world does not
     * keep a chest the plugin does not know about.
     */
    private void rollbackFailedGeneration(Block block, ArmorStand[] holos) {
        if (holos != null) {
            for (ArmorStand holo : holos) {
                if (holo != null) {
                    holo.remove();
                }
            }
        }
        block.setType(Material.AIR);
    }

    private void clearEventDropsAndPlayerInventory(PlayerDeathEvent e, Player p) {
        // Direct removeIf: no intermediate list needed
        e.getDrops().removeIf(drop -> drop != null && !isIgnoredItem(drop));

        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && !isIgnoredItem(item)) {
                p.getInventory().removeItem(item);
            }
        }
    }

    private void maybeSendPosition(Player p, Block b) {
        if (config.getBoolean(ConfigKey.DISPLAY_POSITION_ON_DEATH)) {
            p.sendMessage(local.prefixed("death.position", b.getX(), b.getY(), b.getZ()));
        }
    }

    private void persistAndLog(Player p, Block b, ItemStack[] itemsToStore) {

        generateLog("New deadchest for [" + p.getName() + "] in " + b.getWorld().getName() +
                " at X:" + b.getX() + " Y:" + b.getY() + " Z:" + b.getZ());
        generateLog("Chest content : " + Arrays.asList(itemsToStore));

        if (config.getBoolean(ConfigKey.LOG_DEADCHEST_ON_CONSOLE)) {
            log.info("New deadchest for [" + p.getName() + "] at X:" + b.getX() + " Y:" + b.getY() + " Z:" + b.getZ());
        }
    }

    public static boolean worldGuardCheck(Player p) {
        if (wgsdc != null) {
            return wgsdc.worldGuardChecker(p);
        }
        return true;
    }
}
