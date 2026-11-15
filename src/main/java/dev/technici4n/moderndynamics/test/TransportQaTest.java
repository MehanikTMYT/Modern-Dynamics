/*
 * Modern Dynamics
 * Copyright (C) 2021 shartte & Technici4n
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package dev.technici4n.moderndynamics.test;

import dev.technici4n.moderndynamics.init.MdBlocks;
import dev.technici4n.moderndynamics.init.MdItems;
import dev.technici4n.moderndynamics.network.item.MaxParticipant;
import dev.technici4n.moderndynamics.test.framework.MdGameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Fleet transport QA (26.1.2). Every assertion here is on actual resource counts:
 * nothing is declared passing without the resources really having moved (or really
 * having stayed put, for the "receiver full" case).
 */
public class TransportQaTest {
    /** Rolled-back probes must not advance the extractor's round-robin path count. */
    @MdGameTest
    public void roundRobinTransactionRollback(MdGameTestHelper helper) {
        var participant = new MaxParticipant();
        try (var tx = Transaction.openRoot()) {
            participant.addEntry(7, tx);
        }
        expectMax(helper, participant, 0);
        try (var root = Transaction.openRoot()) {
            participant.addEntry(2, root);
            try (var nested = Transaction.open(root)) {
                participant.addEntry(9, nested);
            }
            expectMax(helper, participant, 2);
            try (var nested = Transaction.open(root)) {
                participant.addEntry(4, nested);
                nested.commit();
            }
            expectMax(helper, participant, 4);
            // The enclosing root aborts, including the committed nested probe.
        }
        expectMax(helper, participant, 0);
        try (var root = Transaction.openRoot()) {
            participant.addEntry(3, root);
            participant.addEntry(1, root);
            root.commit();
        }
        expectMax(helper, participant, 3);
        try (var root = Transaction.openRoot()) {
            participant.addEntry(8, root);
        }
        expectMax(helper, participant, 3);
        helper.succeed();
    }

    private static void expectMax(MdGameTestHelper helper, MaxParticipant participant, int expected) {
        if (participant.getMax() != expected) {
            helper.fail("Round-robin checked paths: expected " + expected + ", got " + participant.getMax(), BlockPos.ZERO);
        }
    }

    private static int countItem(MdGameTestHelper helper, BlockPos pos, Item item) {
        var container = helper.getBlockEntity(pos, ChestBlockEntity.class);
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); ++i) {
            var stack = container.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void expectItems(MdGameTestHelper helper, BlockPos pos, Item item, int expected, String what) {
        int actual = countItem(helper, pos, item);
        if (actual != expected) {
            helper.fail(what + ": expected " + expected + " " + item + " at " + pos + " but found " + actual, pos);
        }
    }

    /**
     * Item transfer between two containers, with strict conservation:
     * every diamond that leaves the source must arrive in the target, no more, no less.
     */
    @MdGameTest(timeoutTicks = 400)
    public void itemTransferBetweenContainers(MdGameTestHelper helper) {
        var source = new BlockPos(0, 1, 0);
        var target = new BlockPos(2, 1, 0);
        helper.setBlock(source, Blocks.CHEST);
        helper.setBlock(target, Blocks.CHEST);

        helper.pipe(new BlockPos(1, 1, 0), MdBlocks.ITEM_PIPE)
                .attachment(Direction.WEST, MdItems.EXTRACTOR)
                .configureItemIo(Direction.WEST, io -> io.setMaxItemsExtracted(4));

        helper.getBlockEntity(source, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 8));
        helper.getBlockEntity(source, ChestBlockEntity.class).setItem(1, new ItemStack(Items.DIAMOND, 4));

        int initial = 8 + 4;

        helper.startSequence()
                .thenWaitUntil(() -> expectItems(helper, target, Items.DIAMOND, initial, "target must receive everything"))
                .thenExecute(() -> {
                    expectItems(helper, source, Items.DIAMOND, 0, "source must be emptied");
                    expectItems(helper, target, Items.DIAMOND, initial, "no duplication allowed");
                })
                .thenSucceed();
    }

    /**
     * Fluid transfer between two reservoirs (cauldrons), with strict conservation of the bucket.
     */
    @MdGameTest(timeoutTicks = 200)
    public void fluidTransferBetweenReservoirs(MdGameTestHelper helper) {
        var target = new BlockPos(0, 1, 0);
        var source = new BlockPos(3, 1, 0);
        helper.setBlock(target, Blocks.CAULDRON);
        helper.setBlock(source, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));

        helper.pipe(new BlockPos(1, 1, 0), MdBlocks.FLUID_PIPE);
        helper.pipe(new BlockPos(2, 1, 0), MdBlocks.FLUID_PIPE)
                .attachment(Direction.EAST, MdItems.EXTRACTOR);

        helper.startSequence()
                .thenWaitUntil(() -> helper.checkFluid(target, Fluids.WATER, FluidType.BUCKET_VOLUME))
                .thenExecute(() -> {
                    var sourceState = helper.getBlockState(source);
                    if (sourceState.getBlock() != Blocks.CAULDRON) {
                        helper.fail("source reservoir still holds water: " + sourceState, source);
                    }
                    helper.checkFluid(target, Fluids.WATER, FluidType.BUCKET_VOLUME);
                })
                .thenSucceed();
    }

    /**
     * Transfer must stop while the receiver is completely full: nothing may be lost from the
     * source and nothing may be created in the receiver.
     */
    @MdGameTest(timeoutTicks = 120)
    public void transferStopsWhenReceiverIsFull(MdGameTestHelper helper) {
        var source = new BlockPos(0, 1, 0);
        var target = new BlockPos(2, 1, 0);
        helper.setBlock(source, Blocks.CHEST);
        helper.setBlock(target, Blocks.CHEST);

        helper.pipe(new BlockPos(1, 1, 0), MdBlocks.ITEM_PIPE)
                .attachment(Direction.WEST, MdItems.EXTRACTOR);

        var targetContainer = helper.getBlockEntity(target, ChestBlockEntity.class);
        for (int i = 0; i < targetContainer.getContainerSize(); ++i) {
            targetContainer.setItem(i, new ItemStack(Items.DIAMOND, 64));
        }
        int fullTarget = targetContainer.getContainerSize() * 64;
        helper.getBlockEntity(source, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 64));

        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    expectItems(helper, source, Items.DIAMOND, 64, "full receiver must not pull from the source");
                    expectItems(helper, target, Items.DIAMOND, fullTarget, "full receiver must not gain items");
                })
                .thenSucceed();
    }

    /**
     * Unload and reload the block entities of a running transfer the same way a chunk unload does:
     * save to NBT, remove the block entity, re-create it from the saved data. The transfer has to
     * resume and the total amount of resources must be conserved (in-flight items are part of the
     * saved pipe data).
     */
    @MdGameTest(timeoutTicks = 400)
    public void transferSurvivesUnloadAndReload(MdGameTestHelper helper) {
        var source = new BlockPos(0, 1, 0);
        var pipePos = new BlockPos(1, 1, 0);
        var target = new BlockPos(2, 1, 0);
        helper.setBlock(source, Blocks.CHEST);
        helper.setBlock(target, Blocks.CHEST);
        helper.pipe(pipePos, MdBlocks.ITEM_PIPE)
                .attachment(Direction.WEST, MdItems.EXTRACTOR)
                .configureItemIo(Direction.WEST, io -> io.setMaxItemsExtracted(4));

        helper.getBlockEntity(source, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 8));
        helper.getBlockEntity(source, ChestBlockEntity.class).setItem(1, new ItemStack(Items.DIAMOND, 4));
        int initial = 8 + 4;

        helper.startSequence()
                // let the first extraction round happen, so items are in flight inside the pipe
                .thenIdle(10)
                .thenExecute(() -> {
                    unloadAndReload(helper, source);
                    unloadAndReload(helper, pipePos);
                    unloadAndReload(helper, target);
                })
                .thenWaitUntil(() -> expectItems(helper, target, Items.DIAMOND, initial, "target must receive everything after reload"))
                .thenExecute(() -> {
                    expectItems(helper, source, Items.DIAMOND, 0, "source must be emptied after reload");
                    expectItems(helper, target, Items.DIAMOND, initial, "no duplication after unload/load");
                })
                .thenSucceed();
    }

    /**
     * Saves a block entity to NBT, removes it (this is what a chunk unload does after writing the
     * chunk) and re-creates it from that NBT (what the chunk load does), triggering the regular
     * {@code clearRemoved()} re-registration path.
     */
    private static void unloadAndReload(MdGameTestHelper helper, BlockPos relativePos) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(relativePos);
        var old = level.getBlockEntity(pos);
        if (old == null) {
            helper.fail("No block entity to unload at " + relativePos, relativePos);
            return;
        }

        var state = old.getBlockState();
        var type = old.getType();
        var saved = old.saveWithFullMetadata(level.registryAccess());

        // unload: exactly what LevelChunk/ServerLevel do once the chunk data has been written
        level.removeBlockEntity(pos);
        if (level.getBlockEntity(pos) == old) {
            helper.fail("Block entity still present after unload at " + relativePos, relativePos);
        }

        // load: re-create from NBT and hand it back to the level
        BlockEntity fresh = type.create(pos, state);
        fresh.setBlockState(state);
        fresh.setLevel(level);
        fresh.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        level.setBlockEntity(fresh);
        if (level.getBlockEntity(pos) != fresh) {
            helper.fail("Block entity was not re-instated at " + relativePos, relativePos);
        }
    }
}
