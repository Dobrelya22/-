package com.olegus.arrow.gametest;

import com.olegus.arrow.ArrowMode;
import com.olegus.arrow.OlegusArrowMod;
import com.olegus.arrow.entity.ArrowPathfinder;
import com.olegus.arrow.entity.OlegusArrowEntity;
import com.olegus.arrow.entity.TargetRules;
import com.olegus.arrow.registry.ModAdvancements;
import com.olegus.arrow.registry.ModEntities;
import com.olegus.arrow.registry.ModGameRules;
import com.olegus.arrow.registry.ModItems;
import com.olegus.arrow.registry.ModStats;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * Запуск: ./gradlew runGametest. Каждый тест в своей партии и на своей арене 24×12×24
 * (tools/generate_test_arena.py), чтобы стрелы и мобы разных тестов не мешали друг другу.
 */
public class OlegusArrowGameTests implements FabricGameTest {
    private static final String ARENA = "olegus_arrow_gametest:arena";
    /** Координаты в тестах отсчитываются от середины арены по горизонтали. */
    private static final int ARENA_CENTER = 8;

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x + ARENA_CENTER, y, z + ARENA_CENTER);
    }

    /**
     * Тестовый сервер обновляет сущности только в принудительно загруженных чанках; стрела,
     * вылетевшая за них, замирает. Загружаем чанки вокруг арены с запасом на её петли.
     */
    private static void prepareArena(TestContext context) {
        BlockPos origin = context.getAbsolutePos(at(0, 0, 0));
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                context.getWorld().setChunkForced(chunkX + dx, chunkZ + dz, true);
            }
        }
    }

    @GameTest(templateName = ARENA, batchId = "olegus_crowd")
    public void crowdSpeedIsCappedAtDouble(TestContext context) {
        prepareArena(context);
        context.assertTrue(TargetRules.crowdSpeedMultiplier(1) == 1.0, "Одиночная цель не должна ускорять");
        context.assertTrue(TargetRules.crowdSpeedMultiplier(3) == 1.0, "Три моба — ещё не толпа");
        context.assertTrue(TargetRules.crowdSpeedMultiplier(4) > 1.0, "Четыре моба — уже толпа");
        context.assertTrue(TargetRules.crowdSpeedMultiplier(500) == 2.0, "Ускорение не больше чем вдвое");
        context.complete();
    }

    @GameTest(templateName = ARENA, batchId = "olegus_path")
    public void pathfinderFliesAroundWall(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        BlockPos base = context.getAbsolutePos(at(0, 1, 0));
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
        for (int y = 0; y <= 4; y++) {
            for (int z = 0; z <= 6; z++) {
                world.setBlockState(base.add(3, y, z), Blocks.AIR.getDefaultState());
            }
        }
        context.complete();
    }

    @GameTest(templateName = ARENA, batchId = "olegus_rules")
    public void onlyMobsAreTargets(TestContext context) {
        prepareArena(context);
        PlayerEntity owner = context.createMockPlayer(GameMode.SURVIVAL);
        PlayerEntity stranger = context.createMockPlayer(GameMode.SURVIVAL);
        owner.setPosition(Vec3d.ofBottomCenter(context.getAbsolutePos(at(1, 2, 1))));
        stranger.setPosition(Vec3d.ofBottomCenter(context.getAbsolutePos(at(2, 2, 2))));
        HuskEntity husk = context.spawnEntity(EntityType.HUSK, at(3, 2, 3));
        husk.setAiDisabled(true);
        WolfEntity pet = context.spawnEntity(EntityType.WOLF, at(4, 2, 4));
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

    @GameTest(templateName = ARENA, batchId = "olegus_hunt_all", tickLimit = 900)
    public void allModeKillsEveryMob(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity first = spawnStillHusk(context, at(6, 2, 6));
        HuskEntity second = spawnStillHusk(context, at(-3, 2, 7));
        PigEntity pig = context.spawnEntity(EntityType.PIG, at(7, 2, -2));
        pig.setAiDisabled(true);
        float ownerHealth = owner.getHealth();

        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.ALL, null);
        succeedWhen(context, 900, () -> !first.isAlive() && !second.isAlive() && !pig.isAlive(), () -> {
            context.assertTrue(owner.getHealth() >= ownerHealth, "Стрела ранила владельца");
            arrow.discard();
        }, () -> describe(context, arrow) + " | хаск1 " + describe(context, first) + " | хаск2 "
                + describe(context, second) + " | свинья " + describe(context, pig));
    }

    @GameTest(templateName = ARENA, batchId = "olegus_hunt_one", tickLimit = 600)
    public void targetModeIgnoresOtherMobs(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity chosen = spawnStillHusk(context, at(6, 2, 6));
        HuskEntity bystander = spawnStillHusk(context, at(-3, 2, 6));

        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.TARGET, chosen);
        succeedWhen(context, 600, () -> !chosen.isAlive(), () -> {
            context.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "Стрела задела не ту цель");
            bystander.discard();
            arrow.discard();
        }, () -> describe(context, arrow) + " | цель " + describe(context, chosen));
    }

    /** Цель за стеной до земли: стрела должна облететь её сверху или сбоку и добить цель. */
    @GameTest(templateName = ARENA, batchId = "olegus_wall_target", tickLimit = 900)
    public void targetBehindWallIsReached(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        List<BlockPos> wall = buildWall(context, 4, -1, 6, -4, 10);
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity husk = spawnStillHusk(context, at(8, 2, 3));
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.TARGET, husk);
        succeedWhen(context, 900, 20, () -> !husk.isAlive(), arrow::discard,
                () -> describe(context, arrow) + " | цель " + describe(context, husk),
                () -> removeBlocks(context, wall));
    }

    /** Та же раскладка, что в охоте на всех, но с висящей стеной между игроком и частью мобов. */
    @GameTest(templateName = ARENA, batchId = "olegus_wall_all", tickLimit = 900)
    public void allModeFliesAroundWall(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        List<BlockPos> wall = buildWall(context, 3, 1, 5, 0, 6);
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity first = spawnStillHusk(context, at(6, 2, 6));
        HuskEntity second = spawnStillHusk(context, at(-3, 2, 7));
        PigEntity pig = context.spawnEntity(EntityType.PIG, at(7, 2, -2));
        pig.setAiDisabled(true);
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.ALL, null);
        succeedWhen(context, 900, 20, () -> !first.isAlive() && !second.isAlive() && !pig.isAlive(), arrow::discard,
                () -> describe(context, arrow) + " | хаск1 " + describe(context, first) + " | хаск2 "
                        + describe(context, second) + " | свинья " + describe(context, pig),
                () -> removeBlocks(context, wall));
    }

    @GameTest(templateName = ARENA, batchId = "olegus_whistle", tickLimit = 200)
    public void whistleBringsArrowBack(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        clearInventory(owner.getInventory());
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.ALL, null);
        context.waitAndRun(10, arrow::recall);
        succeedWhen(context, 200, arrow::isRemoved, () -> {
            context.assertTrue(countItems(owner.getInventory(), ModItems.OLEGUS_ARROW) == 1, "Стрела не оказалась в инвентаре");
            context.assertTrue(ModAdvancements.isDone(owner, ModAdvancements.LAUNCH), "Нет достижения за запуск");
        }, () -> describe(context, arrow));
    }

    /** «Все мобы» не трогают жителя, голема, чужого питомца и моба с именем, но указать их вручную можно. */
    @GameTest(templateName = ARENA, batchId = "olegus_friends", tickLimit = 600)
    public void allModeSparesFriends(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity husk = spawnStillHusk(context, at(6, 2, 6));
        VillagerEntity villager = spawnStill(context, EntityType.VILLAGER, at(-4, 2, 5));
        IronGolemEntity golem = spawnStill(context, EntityType.IRON_GOLEM, at(5, 2, -3));
        PigEntity named = spawnStill(context, EntityType.PIG, at(-2, 2, 8));
        named.setCustomName(Text.literal("Хрюша"));
        WolfEntity strangersDog = spawnStill(context, EntityType.WOLF, at(7, 2, 2));
        strangersDog.setOwnerUuid(UUID.randomUUID());
        List<MobEntity> friends = List.of(villager, golem, named, strangersDog);
        for (MobEntity friend : friends) {
            context.assertTrue(!TargetRules.isAutoTarget(friend, owner, 50.0), "Свой моб попал в охоту: " + describe(context, friend));
            context.assertTrue(TargetRules.isValidTarget(friend, owner, 50.0), "Своего моба нельзя указать вручную: " + describe(context, friend));
        }
        context.assertTrue(TargetRules.isAutoTarget(husk, owner, 50.0), "Хаск должен быть целью");

        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.ALL, null);
        succeedWhen(context, 600, 100, () -> !husk.isAlive() && arrow.getStatus() == OlegusArrowEntity.STATUS_WAITING,
                () -> {
                    for (MobEntity friend : friends) {
                        context.assertTrue(friend.getHealth() == friend.getMaxHealth(), "Стрела ранила своего: " + describe(context, friend));
                    }
                }, () -> describe(context, arrow) + " | хаск " + describe(context, husk),
                () -> {
                    arrow.discard();
                    friends.forEach(Entity::discard);
                });
    }

    /** «Охрана» сбивает враждебного моба рядом с владельцем, но не трогает свинью и далёкого хаска. */
    @GameTest(templateName = ARENA, batchId = "olegus_guard_near", tickLimit = 600)
    public void guardModeStrikesNearbyHostiles(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity near = spawnStillHusk(context, at(5, 2, 5));
        // Угол арены: дальше радиуса охраны (12), но в пределах погони (32).
        HuskEntity far = spawnStillHusk(context, at(14, 2, 14));
        PigEntity pig = spawnStill(context, EntityType.PIG, at(-2, 2, 4));
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.GUARD, null);
        succeedWhen(context, 600, 100, () -> !near.isAlive() && arrow.getStatus() == OlegusArrowEntity.STATUS_WAITING,
                () -> {
                    context.assertTrue(far.getHealth() == far.getMaxHealth(), "Охрана погналась за далёким хаском");
                    context.assertTrue(pig.getHealth() == pig.getMaxHealth(), "Охрана тронула свинью");
                }, () -> describe(context, arrow) + " | рядом " + describe(context, near) + " | далеко " + describe(context, far),
                () -> {
                    arrow.discard();
                    far.discard();
                    pig.discard();
                });
    }

    /** «Охрана» достаёт и далёкого моба, если он напал на владельца. */
    @GameTest(templateName = ARENA, batchId = "olegus_guard_attacker", tickLimit = 600)
    public void guardModeDefendsAgainstAttacker(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity attacker = spawnStillHusk(context, at(14, 2, 14));
        owner.setAttacker(attacker);
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.GUARD, null);
        succeedWhen(context, 600, 100, () -> !attacker.isAlive(), arrow::discard,
                () -> describe(context, arrow) + " | напавший " + describe(context, attacker), () -> { });
    }

    /** Владелец ударил моба — ждущая цели стрела приходит на помощь. */
    @GameTest(templateName = ARENA, batchId = "olegus_assist", tickLimit = 600)
    public void attackingMobCallsWaitingArrow(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        HuskEntity enemy = spawnStillHusk(context, at(6, 2, 5));
        HuskEntity bystander = spawnStillHusk(context, at(-4, 2, 6));
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.TARGET, null);
        context.waitAndRun(10, () -> AttackEntityCallback.EVENT.invoker().interact(owner, world, Hand.MAIN_HAND, enemy, null));
        succeedWhen(context, 600, 100, () -> !enemy.isAlive(),
                () -> context.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "Стрела задела не того"),
                () -> describe(context, arrow) + " | враг " + describe(context, enemy),
                () -> {
                    arrow.discard();
                    bystander.discard();
                });
    }

    /** Добыча сражённого моба прилетает в инвентарь владельца; победа идёт в статистику. */
    @GameTest(templateName = ARENA, batchId = "olegus_loot", tickLimit = 600)
    public void lootFliesToOwner(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        ServerPlayerEntity owner = createOwner(context);
        clearInventory(owner.getInventory());
        HuskEntity husk = spawnStillHusk(context, at(6, 2, 6));
        husk.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
        husk.setEquipmentDropChance(EquipmentSlot.MAINHAND, 2.0F);
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, ItemStack.EMPTY, ArrowMode.TARGET, husk);
        succeedWhen(context, 600, 100, () -> !husk.isAlive() && countItems(owner.getInventory(), Items.DIAMOND) == 1,
                () -> {
                    int kills = owner.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(ModStats.ARROW_KILLS));
                    context.assertTrue(kills == 1, "Победа не попала в статистику: " + kills);
                    context.assertTrue(ModAdvancements.isDone(owner, ModAdvancements.LOOT), "Нет достижения за добычу");
                },
                () -> describe(context, arrow) + " | хаск " + describe(context, husk)
                        + " | алмазов " + countItems(owner.getInventory(), Items.DIAMOND),
                () -> {
                    arrow.discard();
                    clearInventory(owner.getInventory());
                });
    }

    /** Правило olegusArrowDuration: короткий полёт кончается финалом и падением предметом. */
    @GameTest(templateName = ARENA, batchId = "olegus_duration", tickLimit = 600)
    public void durationRuleShortensFlight(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        world.getGameRules().get(ModGameRules.DURATION).set(10, world.getServer());
        ServerPlayerEntity owner = createOwner(context);
        OlegusArrowEntity arrow = OlegusArrowEntity.launch(world, owner, new ItemStack(ModItems.OLEGUS_ARROW),
                ArrowMode.ALL, null);
        context.assertTrue(arrow.getMaxLife() == 10 * 20, "Длительность из правила не применилась: " + arrow.getMaxLife());
        Box around = owner.getBoundingBox().expand(8.0);
        succeedWhen(context, 600, 100, arrow::isRemoved, () -> {
            List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, around,
                    item -> item.getStack().isOf(ModItems.OLEGUS_ARROW));
            context.assertTrue(!drops.isEmpty(), "Стрела не упала предметом");
            drops.forEach(ItemEntity::discard);
        }, () -> describe(context, arrow), () -> world.getGameRules().get(ModGameRules.DURATION).set(40, world.getServer()));
    }

    @GameTest(templateName = ARENA, batchId = "olegus_orphan")
    public void ownerlessArrowDropsAsItem(TestContext context) {
        prepareArena(context);
        ServerWorld world = context.getWorld();
        Vec3d pos = Vec3d.ofCenter(context.getAbsolutePos(at(1, 2, 1)));
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

    /**
     * Проверяет условие каждые 5 тиков; как только оно выполнилось — финальные проверки и успех.
     * Проверки расписаны заранее: планировать новые задачи изнутри задачи теста небезопасно.
     * Раз в {@code traceEvery} тиков состояние пишется в лог, а если условие так и не выполнилось —
     * тест падает с этим состоянием в сообщении. {@code cleanup} выполняется в любом случае, чтобы
     * следующие тесты на том же месте начинали с чистого мира.
     */
    private static void succeedWhen(TestContext context, int tickLimit, int traceEvery, BooleanSupplier condition,
                                    Runnable onSuccess, Supplier<String> state, Runnable cleanup) {
        boolean[] done = {false};
        for (int tick = 5; tick < tickLimit - 5; tick += 5) {
            int at = tick;
            context.waitAndRun(tick, () -> {
                if (done[0]) {
                    return;
                }
                if (condition.getAsBoolean()) {
                    done[0] = true;
                    cleanup.run();
                    onSuccess.run();
                    context.complete();
                } else if (at % traceEvery == 0) {
                    OlegusArrowMod.LOGGER.info("[тест, тик {}] {}", at, state.get());
                }
            });
        }
        context.waitAndRun(tickLimit - 3, () -> {
            if (!done[0]) {
                done[0] = true;
                String finalState = state.get();
                cleanup.run();
                context.assertTrue(condition.getAsBoolean(), "Не дождались: " + finalState);
            }
        });
    }

    private static void succeedWhen(TestContext context, int tickLimit, BooleanSupplier condition, Runnable onSuccess,
                                    Supplier<String> state) {
        succeedWhen(context, tickLimit, 100, condition, onSuccess, state, () -> { });
    }

    /** Стена из камня в плоскости x = {@code x}; возвращает её блоки, чтобы потом убрать. */
    private static List<BlockPos> buildWall(TestContext context, int x, int fromY, int toY, int fromZ, int toZ) {
        List<BlockPos> blocks = new java.util.ArrayList<>();
        for (int y = fromY; y <= toY; y++) {
            for (int z = fromZ; z <= toZ; z++) {
                BlockPos pos = context.getAbsolutePos(at(x, y, z));
                context.getWorld().setBlockState(pos, Blocks.STONE.getDefaultState());
                blocks.add(pos);
            }
        }
        return blocks;
    }

    private static void removeBlocks(TestContext context, List<BlockPos> blocks) {
        for (BlockPos pos : blocks) {
            context.getWorld().setBlockState(pos, Blocks.AIR.getDefaultState());
        }
    }

    private static String describe(TestContext context, Entity entity) {
        BlockPos pos = entity.getBlockPos().subtract(context.getAbsolutePos(at(0, 0, 0)));
        String base = EntityType.getId(entity.getType()).getPath() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + (entity.isRemoved() ? " удалён" : "");
        if (entity instanceof OlegusArrowEntity arrow) {
            return base + " статус " + arrow.getStatus() + " цель#" + arrow.getTargetId() + " побед " + arrow.getKills()
                    + " жизнь " + arrow.getSyncedLife() + " скорость " + String.format(java.util.Locale.ROOT, "%.2f", arrow.getVelocity().length());
        }
        if (entity instanceof LivingEntity living) {
            return base + " id" + entity.getId() + " hp " + living.getHealth();
        }
        return base;
    }

    private static ServerPlayerEntity createOwner(TestContext context) {
        ServerPlayerEntity owner = context.createMockCreativeServerPlayerInWorld();
        owner.setPosition(Vec3d.ofBottomCenter(context.getAbsolutePos(at(1, 2, 1))));
        return owner;
    }

    private static HuskEntity spawnStillHusk(TestContext context, BlockPos pos) {
        return spawnStill(context, EntityType.HUSK, pos);
    }

    private static <T extends MobEntity> T spawnStill(TestContext context, EntityType<T> type, BlockPos pos) {
        T mob = context.spawnEntity(type, pos);
        mob.setAiDisabled(true);
        return mob;
    }

    private static void clearInventory(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.setStack(slot, ItemStack.EMPTY);
        }
    }

    private static int countItems(PlayerInventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                count += inventory.getStack(slot).getCount();
            }
        }
        return count;
    }
}
