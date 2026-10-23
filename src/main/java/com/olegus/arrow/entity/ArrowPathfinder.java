package com.olegus.arrow.entity;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Поиск пути A* по воздушным блокам для летящей стрелы: 26 направлений, без срезания углов.
 * Нужен, когда цель за стеной: стрела облетает препятствие, а не бьётся в него.
 */
public final class ArrowPathfinder {
    private static final int MAX_EXPANDED_NODES = 3000;
    private static final int MAX_RANGE = 64;
    /** Чуть «жадная» эвристика: путь почти оптимальный, а ищется заметно быстрее. */
    private static final double HEURISTIC_WEIGHT = 1.25;
    private static final int[][] OFFSETS;
    private static final double[] COSTS;

    static {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        offsets.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        OFFSETS = offsets.toArray(new int[0][]);
        COSTS = new double[OFFSETS.length];
        for (int i = 0; i < OFFSETS.length; i++) {
            int[] o = OFFSETS[i];
            COSTS[i] = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
        }
    }

    private ArrowPathfinder() {
    }

    /** Можно ли пролететь через блок: нет формы столкновения, не лава и чанк загружен. */
    public static boolean isPassable(World world, BlockPos pos) {
        if (world.isOutOfHeightLimit(pos)) {
            return false;
        }
        if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            return false;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /** Путь из блоков от {@code start} до соседства с {@code goal} или {@code null}, если пути нет. */
    @Nullable
    public static List<BlockPos> findPath(World world, BlockPos start, BlockPos goal) {
        if (chebyshev(start, goal) > MAX_RANGE) {
            return null;
        }
        Passability passability = new Passability(world);
        Long2DoubleOpenHashMap bestCost = new Long2DoubleOpenHashMap();
        bestCost.defaultReturnValue(Double.MAX_VALUE);
        Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
        LongOpenHashSet closed = new LongOpenHashSet();
        PriorityQueue<Node> open = new PriorityQueue<>();

        long startKey = start.asLong();
        bestCost.put(startKey, 0.0);
        open.add(new Node(startKey, 0.0, heuristic(start, goal)));
        BlockPos.Mutable current = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_EXPANDED_NODES) {
            Node node = open.poll();
            if (!closed.add(node.key())) {
                continue;
            }
            expanded++;
            current.set(node.key());
            if (chebyshev(current, goal) <= 1) {
                return reconstruct(cameFrom, node.key(), startKey, goal, passability);
            }
            for (int i = 0; i < OFFSETS.length; i++) {
                int[] o = OFFSETS[i];
                neighbor.set(current.getX() + o[0], current.getY() + o[1], current.getZ() + o[2]);
                long key = neighbor.asLong();
                if (closed.contains(key) || chebyshev(neighbor, start) > MAX_RANGE) {
                    continue;
                }
                if (!passability.canStep(current, o)) {
                    continue;
                }
                double cost = node.cost() + COSTS[i];
                if (cost >= bestCost.get(key)) {
                    continue;
                }
                bestCost.put(key, cost);
                cameFrom.put(key, node.key());
                open.add(new Node(key, cost, cost + HEURISTIC_WEIGHT * heuristic(neighbor, goal)));
            }
        }
        return null;
    }

    private static List<BlockPos> reconstruct(Long2LongOpenHashMap cameFrom, long endKey, long startKey,
                                              BlockPos goal, Passability passability) {
        List<BlockPos> path = new ArrayList<>();
        long key = endKey;
        path.add(BlockPos.fromLong(key));
        // Цепочка родителей всегда доходит до старта; ограничение — страховка от зацикливания.
        for (int guard = cameFrom.size(); key != startKey && guard >= 0; guard--) {
            key = cameFrom.get(key);
            path.add(BlockPos.fromLong(key));
        }
        Collections.reverse(path);
        if (!path.get(path.size() - 1).equals(goal) && passability.isPassable(goal.getX(), goal.getY(), goal.getZ())) {
            path.add(goal.toImmutable());
        }
        return path;
    }

    private static double heuristic(Vec3i from, Vec3i to) {
        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int chebyshev(Vec3i a, Vec3i b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
                Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    private record Node(long key, double cost, double priority) implements Comparable<Node> {
        @Override
        public int compareTo(Node other) {
            return Double.compare(this.priority, other.priority);
        }
    }

    /** Кэш проходимости блоков и загруженности чанков на время одного поиска. */
    private static final class Passability {
        private static final byte OPEN = 1;
        private static final byte BLOCKED = 2;

        private final World world;
        private final Long2ByteOpenHashMap blocks = new Long2ByteOpenHashMap();
        private final Long2BooleanOpenHashMap chunks = new Long2BooleanOpenHashMap();
        private final BlockPos.Mutable mutable = new BlockPos.Mutable();

        Passability(World world) {
            this.world = world;
        }

        /** Диагональный шаг разрешён, только если свободны все блоки «коробки» между клетками. */
        boolean canStep(BlockPos from, int[] o) {
            int maxA = o[0] != 0 ? 1 : 0;
            int maxB = o[1] != 0 ? 1 : 0;
            int maxC = o[2] != 0 ? 1 : 0;
            for (int a = 0; a <= maxA; a++) {
                for (int b = 0; b <= maxB; b++) {
                    for (int c = 0; c <= maxC; c++) {
                        if (a == 0 && b == 0 && c == 0) {
                            continue;
                        }
                        if (!this.isPassable(from.getX() + a * o[0], from.getY() + b * o[1], from.getZ() + c * o[2])) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        boolean isPassable(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            byte cached = this.blocks.get(key);
            if (cached != 0) {
                return cached == OPEN;
            }
            boolean open = this.isChunkLoaded(x >> 4, z >> 4) && this.computePassable(x, y, z);
            this.blocks.put(key, open ? OPEN : BLOCKED);
            return open;
        }

        private boolean isChunkLoaded(int chunkX, int chunkZ) {
            long key = ChunkPos.toLong(chunkX, chunkZ);
            if (this.chunks.containsKey(key)) {
                return this.chunks.get(key);
            }
            boolean loaded = this.world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
            this.chunks.put(key, loaded);
            return loaded;
        }

        private boolean computePassable(int x, int y, int z) {
            this.mutable.set(x, y, z);
            if (this.world.isOutOfHeightLimit(this.mutable)) {
                return false;
            }
            BlockState state = this.world.getBlockState(this.mutable);
            if (state.isAir()) {
                return true;
            }
            if (state.getFluidState().isIn(FluidTags.LAVA)) {
                return false;
            }
            return state.getCollisionShape(this.world, this.mutable).isEmpty();
        }
    }
}
