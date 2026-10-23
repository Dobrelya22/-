package com.olegus.arrow.entity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** Кого стреле можно атаковать и в каком порядке. */
public final class TargetRules {
    /** Мобов больше этого числа вокруг цели — уже толпа. */
    public static final int CROWD_THRESHOLD = 3;
    public static final double CROWD_RADIUS = 10.0;
    public static final double MAX_CROWD_SPEED_MULTIPLIER = 2.0;
    /** Насколько далеко от линии взгляда ещё «доводится» прицел. */
    private static final double AIM_ASSIST_RADIANS = Math.toRadians(8.0);

    private TargetRules() {
    }

    /**
     * Цель — только моб (игроки и прочие сущности не подходят), живой, рядом с владельцем
     * и не его питомец или транспорт.
     */
    public static boolean isValidTarget(Entity entity, PlayerEntity owner, double radius) {
        if (!(entity instanceof MobEntity mob)) {
            return false;
        }
        if (!mob.isAlive() || mob.isRemoved() || mob.isInvulnerable()) {
            return false;
        }
        if (mob.getWorld() != owner.getWorld() || mob.squaredDistanceTo(owner) > radius * radius) {
            return false;
        }
        if (mob instanceof Tameable tameable && owner.getUuid().equals(tameable.getOwnerUuid())) {
            return false;
        }
        return !mob.hasPassenger(owner) && !owner.hasPassenger(mob);
    }

    /** Моб под прицелом игрока, с небольшой доводкой, если прицел чуть промахнулся. */
    @Nullable
    public static MobEntity findLookTarget(PlayerEntity player, double range) {
        World world = player.getWorld();
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(range));
        BlockHitResult blockHit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        double maxDistanceSq = blockHit.getType() == HitResult.Type.MISS
                ? range * range
                : eye.squaredDistanceTo(blockHit.getPos());

        MobEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(range),
                candidate -> isValidTarget(candidate, player, range))) {
            Optional<Vec3d> hit = mob.getBoundingBox().expand(0.5).raycast(eye, end);
            double score;
            if (hit.isPresent() && eye.squaredDistanceTo(hit.get()) <= maxDistanceSq + 1.0) {
                score = eye.distanceTo(hit.get());
            } else {
                Vec3d toMob = mob.getBoundingBox().getCenter().subtract(eye);
                double distance = toMob.length();
                if (distance < 1.0E-4) {
                    continue;
                }
                double angle = Math.acos(MathHelper.clamp(look.dotProduct(toMob.multiply(1.0 / distance)), -1.0, 1.0));
                if (angle > AIM_ASSIST_RADIANS || !player.canSee(mob)) {
                    continue;
                }
                // Промах прицела всегда хуже прямого попадания.
                score = 1000.0 + angle * 1000.0 + distance;
            }
            if (score < bestScore) {
                best = mob;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Следующая цель в режиме «все мобы»: сначала враждебные, затем ближайшие к стреле,
     * а видимые напрямую — раньше спрятанных за стенами.
     */
    @Nullable
    public static MobEntity findNextTarget(ServerWorld world, PlayerEntity owner, Entity arrow, double radius,
                                           Set<UUID> excluded) {
        Vec3d from = arrow.getBoundingBox().getCenter();
        MobEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, owner.getBoundingBox().expand(radius),
                candidate -> isValidTarget(candidate, owner, radius) && !excluded.contains(candidate.getUuid()))) {
            Vec3d center = mob.getBoundingBox().getCenter();
            double score = center.squaredDistanceTo(from);
            if (!(mob instanceof Monster)) {
                score += 100.0 * 100.0;
            }
            if (score >= bestScore) {
                continue;
            }
            if (!hasLineOfSight(world, arrow, from, center)) {
                score += 24.0 * 24.0;
            }
            if (score < bestScore) {
                best = mob;
                bestScore = score;
            }
        }
        return best;
    }

    /** Сколько подходящих мобов толпится вокруг цели (включая её саму). */
    public static int countCrowd(ServerWorld world, PlayerEntity owner, MobEntity target, double searchRadius) {
        Box area = target.getBoundingBox().expand(CROWD_RADIUS);
        return world.getEntitiesByClass(MobEntity.class, area,
                mob -> mob.squaredDistanceTo(target) <= CROWD_RADIUS * CROWD_RADIUS
                        && isValidTarget(mob, owner, searchRadius)).size();
    }

    /**
     * В толпе больше трёх мобов стрела разгоняется: +25% за каждого лишнего моба,
     * но не больше чем вдвое. Урон от скорости не зависит.
     */
    public static double crowdSpeedMultiplier(int crowd) {
        if (crowd <= CROWD_THRESHOLD) {
            return 1.0;
        }
        return Math.min(MAX_CROWD_SPEED_MULTIPLIER, 1.0 + 0.25 * (crowd - CROWD_THRESHOLD));
    }

    static boolean hasLineOfSight(World world, Entity entity, Vec3d from, Vec3d to) {
        return world.raycast(new RaycastContext(
                from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity))
                .getType() == HitResult.Type.MISS;
    }
}
