package com.olegus.arrow.gametest;

import com.olegus.arrow.ArrowMode;
import com.olegus.arrow.entity.ArrowPathfinder;
import com.olegus.arrow.entity.OlegusArrowEntity;
import com.olegus.arrow.entity.TargetRules;
import com.olegus.arrow.registry.ModEntities;
import com.olegus.arrow.registry.ModItems;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/** Запуск: ./gradlew runGametest. Каждый тест в своей партии, чтобы стрелы не мешали друг другу. */
public class OlegusArrowGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "olegus_crowd")
    public void crowdSpeedIsCappedAtDouble(TestContext context) {
        context.assertTrue(TargetRules.crowdSpeedMultiplier(1) == 1.0, "Одиночная цель не должна ускорять");
        context.assertTrue(TargetRules.crowdSpeedMultiplier(3) == 1.0, "Три моба — ещё не толпа");
        context.assertTrue(TargetRules.crowdSpeedMultiplier(4) > 1.0, "Четыре моба — уже толпа");
        context.assertTrue(TargetRules.crowdSpeedMultiplier(500) == 2.0, "Ускорение не больше чем вдвое");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "olegus_path")
    public void pathfinderFliesAroundWall(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos base = context.getAbsolutePos(new BlockPos(0, 1, 0));
        for (int y = 0; y <= 4; y++) {
            for (int z = 0; z <= 6; z++) {
                world.setBlockState(base.add(3, y, z), Blocks.STONE.getDefaultState());
            }
        }
        BlockPos start = base.add(1, 2, 3);
        BlockPos goal = base.add(5, 2, 3);
        world.setBlockState(start, Blocks.AIR.getDefaultState());
        world.setBlockState(goal, Blocks.AIR.getDefaultState());

        List<BlockPos> path = ArrowPathfinder.findPath(world, start, goal);
        context.assertTrue(path != null, "Путь вокруг стены не найден");
        for (BlockPos pos : path) {
            context.assertTrue(ArrowPathfinder.isPassable(world, pos), "Путь проходит сквозь блок " + pos);
        }
        BlockPos last = path.get(path.size() - 1);
        context.assertTrue(last.getManhattanDistance(goal) <= 3, "Путь не доходит до цели");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "olegus_rules")
    public void onlyMobsAreTargets(TestContext context) {
        PlayerEntity owner = context.createMockPlayer(GameMode.SURVIVAL);
        PlayerEntity stranger = context.createMockPlayer(GameMode.SURVIVAL);
        owner.setPosition(Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, 2, 1))));
        stranger.setPosition(Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(2, 2, 2))));
        HuskEntity husk = context.spawnEntity(EntityType.HUSK, new BlockPos(3, 2, 3));
        husk.setAiDisabled(true);
        WolfEntity pet = context.spawnEntity(EntityType.WOLF, new BlockPos(4, 2, 4));
        pet.setAiDisabled(true);
        pet.setOwnerUuid(owner.getUuid());

        context.assertTrue(TargetRules.isValidTarget(husk, owner, 50.0), "Хаск должен быть целью");
        context.assertTrue(!TargetRules.isValidTarget(stranger, owner, 50.0), "Игроков стрела не трогает");
        context.assertTrue(!TargetRules.isValidTarget(owner, owner, 50.0), "Владельца стрела не трогает");
        context.assertTrue(!TargetRules.isValidTarget(pet, owner, 50.0), "Питомец владельца — не цель");
        context.assertTrue(!TargetRules.isValidTarget(husk, owner, 1.0), "Цель вне радиуса не подходит");
        husk.discard();
        pet.discard();
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "olegus_hunt_all", tickLimit = 900)
    public void allModeKillsEveryMob(TestContext context) {
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity first = spawnStillHusk(context, new BlockPos(6, 2, 6));
        HuskEntity second = spawnStillHusk(context, new BlockPos(-3, 2, 7));
        PigEntity pig = context.spawnEntity(EntityType.PIG, new BlockPos(7, 2, -2));
        pig.setAiDisabled(true);
        float ownerHealth = owner.getHealth();

        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.ALL, null);
        context.succeedWhen(() -> {
            context.assertTrue(!first.isAlive() && !second.isAlive() && !pig.isAlive(), "Не все мобы повержены");
            context.assertTrue(owner.getHealth() >= ownerHealth, "Стрела ранила владельца");
            arrow.discard();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "olegus_hunt_one", tickLimit = 600)
    public void targetModeIgnoresOtherMobs(TestContext context) {
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity chosen = spawnStillHusk(context, new BlockPos(6, 2, 6));
        HuskEntity bystander = spawnStillHusk(context, new BlockPos(-3, 2, 6));

        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.TARGET, chosen);
        context.succeedWhen(() -> {
            context.assertTrue(!chosen.isAlive(), "Выбранная цель ещё жива");
            context.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "Стрела задела не ту цель");
            bystander.discard();
            arrow.discard();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "olegus_whistle", tickLimit = 200)
    public void whistleBringsArrowBack(TestContext context) {
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        clearInventory(owner.getInventory());
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.ALL, null);
        context.waitAndRun(10, arrow::recall);
        context.succeedWhen(() -> {
            context.assertTrue(arrow.isRemoved(), "Стрела не вернулась на свист");
            context.assertTrue(countArrows(owner.getInventory()) == 1, "Стрела не оказалась в инвентаре");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "olegus_orphan")
    public void ownerlessArrowDropsAsItem(TestContext context) {
        ServerWorld world = context.getWorld();
        Vec3d pos = Vec3d.ofCenter(context.getAbsolutePos(new BlockPos(1, 2, 1)));
        OlegusArrowEntity arrow = new OlegusArrowEntity(ModEntities.OLEGUS_ARROW, world);
        arrow.setPosition(pos);
        arrow.configure(UUID.randomUUID(), new ItemStack(ModItems.OLEGUS_ARROW), ArrowMode.ALL);
        world.spawnEntity(arrow);
        context.waitAndRun(3, () -> {
            context.assertTrue(arrow.isRemoved(), "Стрела без владельца не исчезла");
            List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, new Box(pos, pos).expand(3.0),
                    item -> item.getStack().isOf(ModItems.OLEGUS_ARROW));
            context.assertTrue(!drops.isEmpty(), "Стрела не упала предметом");
            drops.forEach(ItemEntity::discard);
            context.complete();
        });
    }

    private static ServerPlayerEntity createOwner(TestContext context) {
        ServerPlayerEntity owner = context.createMockCreativeServerPlayerInWorld();
        owner.setPosition(Vec3d.ofBottomCenter(context.getAbsolutePos(new BlockPos(1, 2, 1))));
        return owner;
    }

    private static HuskEntity spawnStillHusk(TestContext context, BlockPos pos) {
        HuskEntity husk = context.spawnEntity(EntityType.HUSK, pos);
        husk.setAiDisabled(true);
        return husk;
    }

    private static void clearInventory(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.setStack(slot, ItemStack.EMPTY);
        }
    }

    private static int countArrows(PlayerInventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(ModItems.OLEGUS_ARROW)) {
                count += inventory.getStack(slot).getCount();
            }
        }
        return count;
    }
}
