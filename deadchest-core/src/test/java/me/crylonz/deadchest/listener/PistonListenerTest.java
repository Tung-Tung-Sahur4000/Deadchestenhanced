package me.crylonz.deadchest.listener;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.block.BlockMock;
import me.crylonz.deadchest.ChestData;
import me.crylonz.deadchest.DeadChestLoader;
import me.crylonz.deadchest.db.InMemoryChestStore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PistonListenerTest {

    private ServerMock server;
    private PistonListener listener;
    private InMemoryChestStore deadChest;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        listener = new PistonListener();
        deadChest = DeadChestLoader.getChestDataCache();
        deadChest.setChestData(new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testPistonCancelledWhenPushingHeadWithChestData() {
        Block piston = new BlockMock(Material.PISTON);

        World world = server.addSimpleWorld("world");
        Location loc = new Location(world, 10, 64, 10);

        BlockMock headBlock = new BlockMock(Material.PLAYER_HEAD, loc);

        ChestData cd = mock(ChestData.class);
        when(cd.getChestLocation()).thenReturn(loc);
        deadChest.addChestData(cd);

        List<Block> moved = new ArrayList<>();
        moved.add(headBlock);

        BlockPistonExtendEvent event = new BlockPistonExtendEvent(
                piston, moved, BlockFace.NORTH
        );

        listener.onBlockPistonExtendEvent(event);

        assertTrue(event.isCancelled(), "L'événement doit être annulé si le piston pousse une tombe");
    }

    @Test
    void testPistonCancelledWhenGraveIsBehindAnotherHead() {
        // The loop used to return on the first untracked head, leaving the graves
        // further down the pushed line unprotected.
        Block piston = new BlockMock(Material.PISTON);
        World world = server.addSimpleWorld("world");

        Location plainHeadLoc = new Location(world, 20, 64, 20);
        Location graveLoc = new Location(world, 21, 64, 20);

        BlockMock plainHead = new BlockMock(Material.PLAYER_HEAD, plainHeadLoc);
        BlockMock graveHead = new BlockMock(Material.PLAYER_HEAD, graveLoc);

        ChestData cd = mock(ChestData.class);
        when(cd.getChestLocation()).thenReturn(graveLoc);
        deadChest.addChestData(cd);

        List<Block> moved = new ArrayList<>();
        moved.add(plainHead);
        moved.add(graveHead);

        BlockPistonExtendEvent event = new BlockPistonExtendEvent(piston, moved, BlockFace.NORTH);

        listener.onBlockPistonExtendEvent(event);

        assertTrue(event.isCancelled(), "A deadchest behind another head must still be protected");
    }

    @Test
    void testPistonNotCancelledWhenPushingOtherBlock() {
        Block piston = new BlockMock(Material.PISTON);
        Block dirt = new BlockMock(Material.DIRT);

        List<Block> moved = new ArrayList<>();
        moved.add(dirt);

        BlockPistonExtendEvent event = new BlockPistonExtendEvent(
                piston, moved, BlockFace.NORTH
        );

        listener.onBlockPistonExtendEvent(event);

        assertFalse(event.isCancelled(), "L'événement ne doit pas être annulé si le piston pousse un bloc normal");
    }
}
