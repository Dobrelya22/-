package com.andryushka.arrow;

import com.andryushka.arrow.registry.ModAdvancements;
import com.andryushka.arrow.registry.ModGameRules;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/**
 * Добыча мобов, сражённых стрелой, прилетает к владельцу радужным лучом: предметы — сразу
 * в инвентарь (не влезли — к ногам), опыт — к ногам, где игрок его подберёт (с починкой).
 * Отключается правилом andryushkaArrowBringsLoot.
 */
public final class LootDelivery {
    /** Опыт может выпасть не сразу, а когда тело исчезнет (через 20 тиков) — заглядываем ещё раз. */
    private static final int LATE_SWEEP_TICKS = 22;
    /** Только что выпавшее — чтобы не утащить чужие вещи, лежавшие рядом. */
    private static final int FRESH_AGE = 4;

    private record Sweep(RegistryKey<World> world, Box area, UUID owner, ArrowTrail trail, long dueTick) {
    }

    private static final List<Sweep> PENDING = new ArrayList<>();

    private LootDelivery() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(LootDelivery::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
    }

    /** Вызывается сразу после победы: предметы уже выпали, опыт проверим ещё раз попозже. */
    public static void deliver(ServerWorld world, Box deathArea, ServerPlayerEntity owner, ArrowTrail trail) {
        if (!world.getGameRules().getBoolean(ModGameRules.BRINGS_LOOT)) {
            return;
        }
        Box area = deathArea.expand(1.5);
        sweep(world, area, owner, trail);
        PENDING.add(new Sweep(world.getRegistryKey(), area, owner.getUuid(), trail, world.getTime() + LATE_SWEEP_TICKS));
    }

    private static void tick(MinecraftServer server) {
        Iterator<Sweep> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Sweep sweep = iterator.next();
            ServerWorld world = server.getWorld(sweep.world());
            if (world == null) {
                iterator.remove();
                continue;
            }
            if (world.getTime() < sweep.dueTick()) {
                continue;
            }
            iterator.remove();
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(sweep.owner());
            if (owner != null && owner.getWorld() == world && owner.isAlive()) {
                sweep(world, sweep.area(), owner, sweep.trail());
            }
        }
    }

    private static void sweep(ServerWorld world, Box area, ServerPlayerEntity owner, ArrowTrail trail) {
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, area, item -> item.age <= FRESH_AGE);
        List<ExperienceOrbEntity> orbs = world.getEntitiesByClass(ExperienceOrbEntity.class, area,
                orb -> orb.age <= FRESH_AGE);
        if (items.isEmpty() && orbs.isEmpty()) {
            return;
        }
        Vec3d feet = owner.getPos();
        for (ItemEntity item : items) {
            ItemStack stack = item.getStack();
            Item kind = stack.getItem();
            int before = stack.getCount();
            owner.getInventory().insertStack(stack);
            int taken = before - stack.getCount();
            if (taken > 0) {
                owner.increaseStat(Stats.PICKED_UP.getOrCreateStat(kind), taken);
            }
            if (stack.isEmpty()) {
                item.discard();
            } else {
                item.setStack(stack);
                item.setPosition(feet);
                item.setVelocity(Vec3d.ZERO);
                item.resetPickupDelay();
            }
        }
        for (ExperienceOrbEntity orb : orbs) {
            orb.setPosition(feet.x, feet.y + 0.3, feet.z);
            orb.setVelocity(Vec3d.ZERO);
        }
        beam(world, area.getCenter(), owner.getPos().add(0.0, owner.getHeight() * 0.6, 0.0), trail);
        world.playSound(null, feet.x, feet.y, feet.z, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS,
                0.35F, 1.6F + world.getRandom().nextFloat() * 0.3F);
        if (!items.isEmpty()) {
            ModAdvancements.grant(owner, ModAdvancements.LOOT);
        }
    }

    /** Дорожка искр цвета следа от места победы к владельцу. */
    private static void beam(ServerWorld world, Vec3d from, Vec3d to, ArrowTrail trail) {
        Vec3d delta = to.subtract(from);
        int points = Math.min(80, Math.max(6, (int) (delta.length() * 2.0)));
        float hueStart = world.getRandom().nextFloat();
        for (int i = 0; i <= points; i++) {
            double t = i / (double) points;
            Vec3d point = from.add(delta.multiply(t));
            Vector3f color = Rainbow.toVector(trail.color(hueStart + (float) t, 0.8F));
            world.spawnParticles(new DustParticleEffect(color, 0.7F), point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
