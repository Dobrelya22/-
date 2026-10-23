package com.olegus.arrow.entity;

import com.olegus.arrow.ArrowMode;
import com.olegus.arrow.Rainbow;
import com.olegus.arrow.registry.ModDamageTypes;
import com.olegus.arrow.registry.ModEntities;
import com.olegus.arrow.registry.ModSounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Стрела Олегуса. Летит сама, плавно поворачивает, облетает препятствия и раз за разом
 * пронзает мобов. Через 40 секунд кружит вокруг владельца и падает предметом; на свист
 * отвечает и возвращается в инвентарь.
 */
public class OlegusArrowEntity extends ProjectileEntity {
    /** Активная фаза длится 40 секунд. */
    public static final int ACTIVE_TICKS = 40 * 20;
    /** За 5 секунд до конца звенит колокольчик. */
    public static final int WARNING_TICK = ACTIVE_TICKS - 5 * 20;
    /** Радиус охоты вокруг владельца. */
    public static final double SEARCH_RADIUS = 50.0;

    public static final byte STATUS_HUNTING = 0;
    public static final byte STATUS_WAITING = 1;
    public static final byte STATUS_RETURNING = 2;
    public static final byte STATUS_FINALE = 3;

    private static final int FINALE_CIRCLE_TICKS = 3 * 20;
    private static final int FINALE_TIMEOUT_TICKS = 15 * 20;
    /** Выбранная цель, убежавшая дальше этого от владельца, теряется. */
    private static final double TARGET_LEASH = 64.0;
    /** Стрела, оказавшаяся дальше этого от владельца, сразу возвращается к нему. */
    private static final double OWNER_LEASH = 96.0;
    private static final double BASE_SPEED = 0.8;
    private static final double RETURN_SPEED = 1.1;
    private static final double WAIT_SPEED = 0.5;
    private static final double FINALE_SPEED = 0.6;
    private static final double WAIT_ORBIT_RADIUS = 2.6;
    private static final double FINALE_ORBIT_RADIUS = 1.8;
    /** Насколько вперёд по окружности целится кружащая стрела. */
    private static final double ORBIT_LEAD_RADIANS = 0.8;
    private static final float DAMAGE = 8.0F;
    private static final float HUNT_TURN_DEGREES = 14.0F;
    private static final float CLOSE_TURN_DEGREES = 40.0F;
    private static final int HIT_COOLDOWN = 10;
    private static final int RESPOND_DELAY = 8;
    private static final int UNREACHABLE_TICKS = 100;
    private static final int PATH_RECHECK_TICKS = 10;
    private static final int PATH_RETRY_TICKS = 20;
    private static final int LIFE_SYNC_INTERVAL = 5;
    private static final Vec3d[] ESCAPE_DIRECTIONS = createEscapeDirections();

    private static final TrackedData<Optional<UUID>> OWNER_UUID =
            DataTracker.registerData(OlegusArrowEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Integer> LIFE =
            DataTracker.registerData(OlegusArrowEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Byte> STATUS =
            DataTracker.registerData(OlegusArrowEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Float> BOOST =
            DataTracker.registerData(OlegusArrowEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private enum Phase { ACTIVE, RETURNING, FINALE }

    /** Куда и как лететь в этом тике. */
    private record Plan(Vec3d destination, double speed, float turnDegrees, @Nullable MobEntity target,
                        boolean catchOwner) {
    }

    private Phase phase = Phase.ACTIVE;
    private ArrowMode mode = ArrowMode.TARGET;
    /** Предмет, которым стрела снова станет; пустой, если её выпустили в творческом режиме. */
    private ItemStack stack = ItemStack.EMPTY;
    private int life;
    private int finaleTicks;
    private int circleTicks;
    @Nullable
    private UUID targetUuid;
    @Nullable
    private MobEntity cachedTarget;
    private final Map<UUID, Integer> unreachable = new HashMap<>();
    private int hitCooldown;
    private int respondDelay = -1;
    private int rescanCooldown;
    private int crowdCheckCooldown;
    private double crowdMultiplier = 1.0;
    private double speedMultiplier = 1.0;

    @Nullable
    private List<BlockPos> path;
    private int pathIndex;
    private int pathRecheck;
    @Nullable
    private BlockPos pathGoal;
    private boolean pathFailed;

    private Vec3d stuckAnchor = Vec3d.ZERO;
    private int stuckSeconds;
    private int phasingTicks;

    @Nullable
    private Vec3d lastTrailPos;
    private double trailDistance;

    public OlegusArrowEntity(EntityType<? extends OlegusArrowEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
    }

    /** Выпускает стрелу из глаз игрока по направлению взгляда. */
    public static OlegusArrowEntity launch(ServerWorld world, ServerPlayerEntity owner, ItemStack stack,
                                           ArrowMode mode, @Nullable MobEntity target) {
        OlegusArrowEntity arrow = new OlegusArrowEntity(ModEntities.OLEGUS_ARROW, world);
        arrow.configure(owner.getUuid(), stack, mode);
        arrow.setOwner(owner);
        Vec3d look = owner.getRotationVec(1.0F);
        Vec3d center = owner.getEyePos().add(look.multiply(0.8));
        arrow.setPosition(center.x, center.y - arrow.getHeight() / 2.0, center.z);
        arrow.setVelocity(look.multiply(BASE_SPEED));
        arrow.alignToVelocity();
        arrow.prevYaw = arrow.getYaw();
        arrow.prevPitch = arrow.getPitch();
        if (target != null) {
            arrow.setArrowTarget(target);
        }
        world.spawnEntity(arrow);
        world.playSound(null, center.x, center.y, center.z, ModSounds.LAUNCH, SoundCategory.PLAYERS, 1.0F, 1.0F);
        return arrow;
    }

    public void configure(UUID ownerUuid, ItemStack stack, ArrowMode mode) {
        this.dataTracker.set(OWNER_UUID, Optional.of(ownerUuid));
        this.stack = stack.copy();
        this.mode = mode;
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(OWNER_UUID, Optional.empty());
        builder.add(LIFE, 0);
        builder.add(STATUS, STATUS_WAITING);
        builder.add(BOOST, 1.0F);
    }

    // ---------------------------------------------------------------- состояние для клиента и команд

    public Optional<UUID> getArrowOwnerUuid() {
        return this.dataTracker.get(OWNER_UUID);
    }

    public boolean isOwnedBy(UUID uuid) {
        return this.getArrowOwnerUuid().map(uuid::equals).orElse(false);
    }

    /** Сколько тиков прошло в активной фазе (на клиенте обновляется раз в несколько тиков). */
    public int getSyncedLife() {
        return this.dataTracker.get(LIFE);
    }

    public byte getStatus() {
        return this.dataTracker.get(STATUS);
    }

    /** Текущее ускорение в толпе: от 1 до 2. */
    public float getBoost() {
        return this.dataTracker.get(BOOST);
    }

    public ArrowMode getMode() {
        return this.mode;
    }

    public void setMode(ArrowMode mode) {
        this.mode = mode;
        this.rescanCooldown = 0;
    }

    /** Указать цель. В режиме «все мобы» она атакуется первой, затем охота продолжается. */
    public boolean designate(MobEntity target) {
        if (this.phase == Phase.FINALE) {
            return false;
        }
        if (this.phase == Phase.RETURNING) {
            if (this.life >= ACTIVE_TICKS) {
                return false;
            }
            this.phase = Phase.ACTIVE;
            this.respondDelay = -1;
        }
        this.unreachable.remove(target.getUuid());
        this.setArrowTarget(target);
        return true;
    }

    /** Свист: стрела отвечает, бросает атаку и летит к владельцу. */
    public void recall() {
        if (this.isRemoved() || this.phase == Phase.RETURNING) {
            return;
        }
        this.phase = Phase.RETURNING;
        this.clearTarget();
        this.respondDelay = RESPOND_DELAY;
    }

    /** Кладёт стрелу в инвентарь владельца (или под ноги, если места нет) и убирает сущность. */
    public void returnToOwner(ServerPlayerEntity owner, boolean effects) {
        if (this.isRemoved()) {
            return;
        }
        if (effects && this.respondDelay > 0 && this.getWorld() instanceof ServerWorld world) {
            this.respond(world);
        }
        if (!this.stack.isEmpty()) {
            ItemStack returned = this.stack;
            this.stack = ItemStack.EMPTY;
            owner.getInventory().insertStack(returned);
            if (!returned.isEmpty()) {
                owner.dropItem(returned, false);
            }
        }
        if (effects) {
            owner.getWorld().playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.ENTITY_ITEM_PICKUP,
                    SoundCategory.PLAYERS, 0.6F, 1.4F);
        }
        this.discard();
    }

    // ---------------------------------------------------------------- тик

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient) {
            this.spawnTrail();
        } else {
            this.serverTick((ServerWorld) this.getWorld());
        }
    }

    private void serverTick(ServerWorld world) {
        ServerPlayerEntity owner = this.resolveOwner(world);
        if (owner == null || !owner.isAlive()) {
            this.dropAsItem(world);
            return;
        }
        if (owner.getWorld() != world || this.squaredDistanceTo(owner) > OWNER_LEASH * OWNER_LEASH) {
            this.returnToOwner(owner, true);
            return;
        }
        this.tickTimers(world);

        Plan plan = switch (this.phase) {
            case ACTIVE -> this.planHunt(world, owner);
            case RETURNING -> this.planReturn(owner);
            case FINALE -> this.planFinale(world, owner);
        };
        if (plan == null || this.isRemoved()) {
            return;
        }

        Vec3d from = this.getBoundingBox().getCenter();
        this.steer(world, from, plan);
        this.noClip = this.phasingTicks > 0;
        if (this.phasingTicks > 0) {
            this.phasingTicks--;
        }
        this.move(MovementType.SELF, this.getVelocity());
        Vec3d to = this.getBoundingBox().getCenter();
        this.alignToVelocity();
        this.checkStuck(to, plan);

        if (plan.catchOwner() && segmentTouches(owner.getBoundingBox().expand(0.6), from, to)) {
            this.returnToOwner(owner, true);
            return;
        }
        if (plan.target() != null) {
            this.tryStrike(world, owner, plan.target(), from, to);
        }
        this.syncStatus(plan);
    }

    @Nullable
    private ServerPlayerEntity resolveOwner(ServerWorld world) {
        return this.getArrowOwnerUuid()
                .map(uuid -> world.getServer().getPlayerManager().getPlayer(uuid))
                .orElse(null);
    }

    private void tickTimers(ServerWorld world) {
        if (this.hitCooldown > 0) {
            this.hitCooldown--;
        }
        if (this.rescanCooldown > 0) {
            this.rescanCooldown--;
        }
        if (this.respondDelay > 0 && --this.respondDelay == 0) {
            this.respond(world);
        }
        Iterator<Map.Entry<UUID, Integer>> iterator = this.unreachable.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int left = entry.getValue() - 1;
            if (left <= 0) {
                iterator.remove();
            } else {
                entry.setValue(left);
            }
        }
    }

    // ---------------------------------------------------------------- фазы

    @Nullable
    private Plan planHunt(ServerWorld world, ServerPlayerEntity owner) {
        this.life++;
        if (this.life % LIFE_SYNC_INTERVAL == 0 || this.life >= WARNING_TICK) {
            this.dataTracker.set(LIFE, this.life);
        }
        this.ringWarningBell(world, owner);
        if (this.life >= ACTIVE_TICKS) {
            this.enterFinale();
            return this.planFinale(world, owner);
        }

        MobEntity target = this.updateTarget(world, owner);
        this.updateCrowdBoost(world, owner, target);
        if (target == null) {
            return new Plan(this.orbitPoint(world, owner, WAIT_ORBIT_RADIUS), WAIT_SPEED, 25.0F, null, false);
        }
        double speed = BASE_SPEED * this.speedMultiplier;
        // Чем быстрее стрела, тем резче она должна поворачивать, чтобы не промахиваться.
        return new Plan(this.aimPoint(target, speed), speed, HUNT_TURN_DEGREES * (float) this.speedMultiplier,
                target, false);
    }

    private Plan planReturn(ServerPlayerEntity owner) {
        Vec3d chest = owner.getPos().add(0.0, owner.getHeight() * 0.6, 0.0);
        return new Plan(chest, RETURN_SPEED, 30.0F, null, true);
    }

    @Nullable
    private Plan planFinale(ServerWorld world, ServerPlayerEntity owner) {
        this.finaleTicks++;
        if (this.squaredDistanceTo(owner) < 4.5 * 4.5) {
            this.circleTicks++;
        }
        if (this.circleTicks >= FINALE_CIRCLE_TICKS || this.finaleTicks >= FINALE_TIMEOUT_TICKS) {
            this.dropAsItem(world);
            return null;
        }
        return new Plan(this.orbitPoint(world, owner, FINALE_ORBIT_RADIUS), FINALE_SPEED, 35.0F, null, false);
    }

    private void enterFinale() {
        this.phase = Phase.FINALE;
        this.finaleTicks = 0;
        this.circleTicks = 0;
        this.respondDelay = -1;
        this.clearTarget();
    }

    private void ringWarningBell(ServerWorld world, PlayerEntity owner) {
        int sinceWarning = this.life - WARNING_TICK;
        if (sinceWarning != 0 && sinceWarning != 5 && sinceWarning != 10) {
            return;
        }
        float pitch = sinceWarning == 0 ? 1.0F : sinceWarning == 5 ? 1.26F : 1.5F;
        SoundEvent bell = SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
        world.playSound(null, owner.getX(), owner.getEyeY(), owner.getZ(), bell, SoundCategory.PLAYERS, 1.5F, pitch);
    }

    private void respond(ServerWorld world) {
        this.respondDelay = -1;
        Vec3d center = this.getBoundingBox().getCenter();
        world.playSound(null, center.x, center.y, center.z, ModSounds.RESPOND, SoundCategory.PLAYERS, 2.0F, 1.0F);
        world.spawnParticles(ParticleTypes.NOTE, center.x, center.y + 0.4, center.z, 3, 0.3, 0.2, 0.3, 1.0);
    }

    /** Время вышло или владелец пропал: стрела падает предметом, который можно подобрать. */
    private void dropAsItem(ServerWorld world) {
        if (this.isRemoved()) {
            return;
        }
        Vec3d center = this.getBoundingBox().getCenter();
        if (!this.stack.isEmpty()) {
            ItemEntity item = new ItemEntity(world, center.x, center.y, center.z, this.stack);
            this.stack = ItemStack.EMPTY;
            item.setVelocity(0.0, -0.1, 0.0);
            item.setPickupDelay(10);
            world.spawnEntity(item);
        }
        world.spawnParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 16, 0.2, 0.2, 0.2, 0.04);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 1.0F, 1.2F);
        this.discard();
    }

    // ---------------------------------------------------------------- цели

    @Nullable
    private MobEntity updateTarget(ServerWorld world, PlayerEntity owner) {
        MobEntity target = this.getArrowTarget(world);
        double leash = this.mode == ArrowMode.ALL ? SEARCH_RADIUS : TARGET_LEASH;
        if (target != null && !TargetRules.isValidTarget(target, owner, leash)) {
            this.clearTarget();
            target = null;
        }
        if (target == null && this.mode == ArrowMode.ALL && this.rescanCooldown <= 0) {
            this.rescanCooldown = 10;
            target = TargetRules.findNextTarget(world, owner, this, SEARCH_RADIUS, this.unreachable.keySet());
            if (target != null) {
                this.setArrowTarget(target);
            }
        }
        return target;
    }

    @Nullable
    private MobEntity getArrowTarget(ServerWorld world) {
        if (this.targetUuid == null) {
            return null;
        }
        if (this.cachedTarget != null && !this.cachedTarget.isRemoved()
                && this.cachedTarget.getUuid().equals(this.targetUuid)) {
            return this.cachedTarget;
        }
        Entity entity = world.getEntity(this.targetUuid);
        this.cachedTarget = entity instanceof MobEntity mob ? mob : null;
        if (this.cachedTarget == null) {
            this.targetUuid = null;
        }
        return this.cachedTarget;
    }

    private void setArrowTarget(MobEntity target) {
        this.targetUuid = target.getUuid();
        this.cachedTarget = target;
        this.resetPath();
    }

    private void clearTarget() {
        this.targetUuid = null;
        this.cachedTarget = null;
        this.resetPath();
    }

    private void updateCrowdBoost(ServerWorld world, PlayerEntity owner, @Nullable MobEntity target) {
        if (target == null) {
            this.crowdMultiplier = 1.0;
        } else if (--this.crowdCheckCooldown <= 0) {
            this.crowdCheckCooldown = 10;
            this.crowdMultiplier = TargetRules.crowdSpeedMultiplier(
                    TargetRules.countCrowd(world, owner, target, SEARCH_RADIUS));
        }
        this.speedMultiplier = MathHelper.clamp(
                this.speedMultiplier + (this.crowdMultiplier - this.speedMultiplier) * 0.15,
                1.0, TargetRules.MAX_CROWD_SPEED_MULTIPLIER);
    }

    /** Точка удара с небольшим упреждением по движению цели. */
    private Vec3d aimPoint(MobEntity target, double speed) {
        Vec3d center = target.getBoundingBox().getCenter();
        double ticksToReach = Math.min(center.distanceTo(this.getBoundingBox().getCenter()) / Math.max(speed, 0.1), 10.0);
        return center.add(target.getVelocity().multiply(ticksToReach * 0.5));
    }

    /** Точка чуть впереди на окружности вокруг владельца — погоня за ней и есть кружение. */
    private Vec3d orbitPoint(World world, PlayerEntity owner, double radius) {
        double angle = Math.atan2(this.getZ() - owner.getZ(), this.getX() - owner.getX()) + ORBIT_LEAD_RADIANS;
        double y = owner.getY() + owner.getHeight() * 0.8 + Math.sin(this.age * 0.2) * 0.25;
        Vec3d point = new Vec3d(owner.getX() + Math.cos(angle) * radius, y, owner.getZ() + Math.sin(angle) * radius);
        if (ArrowPathfinder.isPassable(world, BlockPos.ofFloored(point))) {
            return point;
        }
        // В тесноте кружим над головой владельца.
        Vec3d above = new Vec3d(owner.getX(), owner.getY() + owner.getHeight() + 0.8, owner.getZ());
        return ArrowPathfinder.isPassable(world, BlockPos.ofFloored(above)) ? above : owner.getEyePos();
    }

    private void tryStrike(ServerWorld world, ServerPlayerEntity owner, MobEntity target, Vec3d from, Vec3d to) {
        if (this.hitCooldown > 0 || !segmentTouches(target.getBoundingBox().expand(0.3), from, to)) {
            return;
        }
        this.hitCooldown = HIT_COOLDOWN;
        // Урон постоянный и не зависит от скорости — в том числе от ускорения в толпе.
        DamageSource source = ModDamageTypes.arrow(world, this, owner);
        if (target.damage(source, DAMAGE)) {
            world.playSound(null, target.getX(), target.getBodyY(0.5), target.getZ(), SoundEvents.ENTITY_ARROW_HIT,
                    SoundCategory.PLAYERS, 1.0F, 1.1F + this.random.nextFloat() * 0.3F);
            Vec3d center = target.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.CRIT, center.x, center.y, center.z, 12, 0.25, 0.25, 0.25, 0.4);
        }
        if (!target.isAlive()) {
            this.clearTarget();
            this.rescanCooldown = 0;
        }
    }

    private static boolean segmentTouches(Box box, Vec3d from, Vec3d to) {
        return box.contains(from) || box.contains(to) || box.raycast(from, to).isPresent();
    }

    // ---------------------------------------------------------------- полёт

    /** Плавно доворачивает скорость к цели, не превышая угловую скорость за тик. */
    private void steer(ServerWorld world, Vec3d center, Plan plan) {
        Vec3d waypoint = this.navigate(world, center, plan.destination());
        if (this.pathFailed && plan.target() != null && this.mode == ArrowMode.ALL) {
            // До цели не добраться — откладываем её и берём следующую.
            this.unreachable.put(plan.target().getUuid(), UNREACHABLE_TICKS);
            this.clearTarget();
            this.rescanCooldown = 0;
        }

        Vec3d velocity = this.getVelocity();
        double currentSpeed = velocity.length();
        Vec3d toWaypoint = waypoint.subtract(center);
        double distance = toWaypoint.length();
        Vec3d heading;
        if (currentSpeed > 1.0E-4) {
            heading = velocity.multiply(1.0 / currentSpeed);
        } else if (distance > 1.0E-4) {
            heading = toWaypoint.multiply(1.0 / distance);
        } else {
            heading = new Vec3d(0.0, 1.0, 0.0);
        }
        Vec3d desired = distance > 1.0E-4 ? toWaypoint.multiply(1.0 / distance) : heading;

        float turnDegrees = plan.turnDegrees();
        double lookahead = Math.max(1.5, currentSpeed * 4.0);
        boolean obstacleAhead = !TargetRules.hasLineOfSight(world, this, center, center.add(heading.multiply(lookahead)));
        if (plan.target() != null) {
            if (this.hitCooldown > HIT_COOLDOWN / 2 && !obstacleAhead) {
                // Пронзив цель, стрела пролетает насквозь и только потом заходит на новый круг.
                desired = heading;
            } else if (center.squaredDistanceTo(plan.destination()) < 5.0 * 5.0) {
                turnDegrees = Math.max(turnDegrees, CLOSE_TURN_DEGREES);
            }
        }
        if (obstacleAhead) {
            // Впереди препятствие — отворачиваем резче обычного.
            turnDegrees *= 3.0F;
        }
        Vec3d newHeading = rotateTowards(heading, desired, Math.toRadians(Math.min(turnDegrees, 90.0F)));

        double targetSpeed = plan.speed();
        if (heading.dotProduct(desired) < 0.0) {
            targetSpeed *= 0.65;
        }
        double step = targetSpeed > currentSpeed ? 0.06 : 0.12;
        double newSpeed = currentSpeed + MathHelper.clamp(targetSpeed - currentSpeed, -step, step);
        this.setVelocity(newHeading.multiply(newSpeed));
    }

    /**
     * Если цель видна — летим прямо. Иначе ищем путь A* и летим к самой дальней видимой точке
     * пути, поэтому траектория огибает препятствия плавно, а не по клеткам.
     */
    private Vec3d navigate(ServerWorld world, Vec3d center, Vec3d destination) {
        this.pathFailed = false;
        if (TargetRules.hasLineOfSight(world, this, center, destination)) {
            this.path = null;
            this.pathRecheck = 0;
            return destination;
        }
        BlockPos goal = BlockPos.ofFloored(destination);
        boolean goalMoved = this.pathGoal == null || !this.pathGoal.isWithinDistance(goal, 2.5);
        if (--this.pathRecheck <= 0 || (this.path != null && goalMoved)) {
            this.pathGoal = goal;
            this.path = ArrowPathfinder.findPath(world, BlockPos.ofFloored(center), goal);
            this.pathIndex = 0;
            this.pathRecheck = this.path != null ? PATH_RECHECK_TICKS : PATH_RETRY_TICKS;
        }
        if (this.path == null) {
            this.pathFailed = true;
            return this.escapeWaypoint(world, center, destination);
        }
        while (this.pathIndex < this.path.size() - 1
                && Vec3d.ofCenter(this.path.get(this.pathIndex)).squaredDistanceTo(center) < 0.8 * 0.8) {
            this.pathIndex++;
        }
        for (int i = Math.min(this.path.size() - 1, this.pathIndex + 6); i > this.pathIndex; i--) {
            Vec3d waypoint = Vec3d.ofCenter(this.path.get(i));
            if (TargetRules.hasLineOfSight(world, this, center, waypoint)) {
                this.pathIndex = i;
                return waypoint;
            }
        }
        return Vec3d.ofCenter(this.path.get(this.pathIndex));
    }

    /** Пути нет: выбираем свободное направление, ближайшее к нужному. */
    private Vec3d escapeWaypoint(World world, Vec3d center, Vec3d destination) {
        Vec3d desired = destination.subtract(center).normalize();
        Vec3d best = null;
        double bestScore = -2.0;
        for (Vec3d direction : ESCAPE_DIRECTIONS) {
            double score = direction.dotProduct(desired);
            if (score <= bestScore) {
                continue;
            }
            if (TargetRules.hasLineOfSight(world, this, center, center.add(direction.multiply(2.5)))) {
                best = direction;
                bestScore = score;
            }
        }
        return best == null ? destination : center.add(best.multiply(2.5));
    }

    private void resetPath() {
        this.path = null;
        this.pathGoal = null;
        this.pathRecheck = 0;
        this.pathFailed = false;
    }

    /** Если стрела 2 секунды почти не двигается, она ненадолго проходит сквозь блоки. */
    private void checkStuck(Vec3d center, Plan plan) {
        if (this.age % 20 != 0) {
            return;
        }
        boolean barelyMoved = center.squaredDistanceTo(this.stuckAnchor) < 0.5 * 0.5;
        boolean farFromGoal = center.squaredDistanceTo(plan.destination()) > 2.0 * 2.0;
        this.stuckSeconds = barelyMoved && farFromGoal ? this.stuckSeconds + 1 : 0;
        if (this.stuckSeconds >= 2) {
            this.phasingTicks = 15;
            this.stuckSeconds = 0;
        }
        this.stuckAnchor = center;
    }

    private void alignToVelocity() {
        Vec3d velocity = this.getVelocity();
        if (velocity.lengthSquared() < 1.0E-7) {
            return;
        }
        // Та же система углов, что у ванильных стрел: её ожидает рендерер.
        this.setYaw((float) Math.toDegrees(Math.atan2(velocity.x, velocity.z)));
        this.setPitch((float) Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalLength())));
    }

    private void syncStatus(Plan plan) {
        byte status = switch (this.phase) {
            case ACTIVE -> plan.target() != null ? STATUS_HUNTING : STATUS_WAITING;
            case RETURNING -> STATUS_RETURNING;
            case FINALE -> STATUS_FINALE;
        };
        this.dataTracker.set(STATUS, status);
        this.dataTracker.set(BOOST, Math.round(this.speedMultiplier * 20.0) / 20.0F);
    }

    /** Поворачивает единичный вектор {@code from} к {@code to} не больше чем на {@code maxAngle} радиан. */
    static Vec3d rotateTowards(Vec3d from, Vec3d to, double maxAngle) {
        double angle = Math.acos(MathHelper.clamp(from.dotProduct(to), -1.0, 1.0));
        if (angle <= maxAngle) {
            return to;
        }
        Vec3d axis = from.crossProduct(to);
        if (axis.lengthSquared() < 1.0E-8) {
            // Векторы противоположны — разворачиваемся через любую перпендикулярную ось.
            axis = from.crossProduct(Math.abs(from.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0));
        }
        Vec3d towards = axis.normalize().crossProduct(from);
        return from.multiply(Math.cos(maxAngle)).add(towards.multiply(Math.sin(maxAngle))).normalize();
    }

    private static Vec3d[] createEscapeDirections() {
        List<Vec3d> directions = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        directions.add(new Vec3d(x, y, z).normalize());
                    }
                }
            }
        }
        return directions.toArray(new Vec3d[0]);
    }

    // ---------------------------------------------------------------- радужный след (клиент)

    private void spawnTrail() {
        Vec3d current = this.getBoundingBox().getCenter();
        Vec3d last = this.lastTrailPos;
        this.lastTrailPos = current;
        if (last == null) {
            return;
        }
        Vec3d delta = current.subtract(last);
        double length = delta.length();
        if (length < 1.0E-3 || length > 12.0) {
            return;
        }
        World world = this.getWorld();
        float scale = 1.1F + 0.3F * (this.getBoost() - 1.0F);
        int steps = MathHelper.clamp(MathHelper.ceil(length / 0.12), 1, 32);
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3d point = last.add(delta.multiply(t));
            // Оттенок меняется и вдоль следа, и со временем — след переливается.
            float hue = (float) ((this.trailDistance + length * t) * 0.045 + this.age * 0.012);
            int rgb = Rainbow.hsvToRgb(hue, 0.9F, 1.0F);
            Vector3f color = new Vector3f(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F,
                    (rgb & 0xFF) / 255.0F);
            world.addImportantParticle(new DustParticleEffect(color, scale), point.x, point.y, point.z, 0.0, 0.0, 0.0);
        }
        this.trailDistance += length;
        if (this.random.nextInt(3) == 0) {
            world.addParticle(ParticleTypes.END_ROD, current.x, current.y, current.z,
                    this.random.nextGaussian() * 0.01, this.random.nextGaussian() * 0.01,
                    this.random.nextGaussian() * 0.01);
        }
    }

    // ---------------------------------------------------------------- свойства сущности

    @Override
    public boolean shouldRender(double distance) {
        return distance < 128.0 * 128.0;
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    /** Не нажимает нажимные плиты и не задевает растяжки. */
    @Override
    public boolean canAvoidTraps() {
        return true;
    }

    @Override
    public boolean canUsePortals(boolean allowVehicles) {
        return false;
    }

    // ---------------------------------------------------------------- сохранение

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        this.getArrowOwnerUuid().ifPresent(uuid -> nbt.putUuid("ArrowOwner", uuid));
        nbt.putString("Phase", this.phase.name());
        nbt.putString("Mode", this.mode.asString());
        nbt.putInt("Life", this.life);
        nbt.putInt("FinaleTicks", this.finaleTicks);
        nbt.putInt("CircleTicks", this.circleTicks);
        if (this.targetUuid != null) {
            nbt.putUuid("Target", this.targetUuid);
        }
        if (!this.stack.isEmpty()) {
            nbt.put("Item", this.stack.encode(this.getRegistryManager()));
        }
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.dataTracker.set(OWNER_UUID,
                nbt.containsUuid("ArrowOwner") ? Optional.of(nbt.getUuid("ArrowOwner")) : Optional.empty());
        this.phase = parsePhase(nbt.getString("Phase"));
        this.mode = ArrowMode.byId(nbt.getString("Mode"));
        this.life = nbt.getInt("Life");
        this.dataTracker.set(LIFE, this.life);
        this.finaleTicks = nbt.getInt("FinaleTicks");
        this.circleTicks = nbt.getInt("CircleTicks");
        this.targetUuid = nbt.containsUuid("Target") ? nbt.getUuid("Target") : null;
        this.stack = nbt.contains("Item", NbtElement.COMPOUND_TYPE)
                ? ItemStack.fromNbt(this.getRegistryManager(), nbt.getCompound("Item")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
    }

    private static Phase parsePhase(String name) {
        for (Phase phase : Phase.values()) {
            if (phase.name().equals(name)) {
                return phase;
            }
        }
        return Phase.ACTIVE;
    }
}
