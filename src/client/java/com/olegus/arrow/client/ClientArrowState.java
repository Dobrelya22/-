package com.olegus.arrow.client;

import com.olegus.arrow.ArrowMode;
import com.olegus.arrow.entity.OlegusArrowEntity;
import com.olegus.arrow.registry.ModItems;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import org.jetbrains.annotations.Nullable;

/** Что клиент знает о своей стреле: режим, летящая стрела и есть ли она в инвентаре. */
public final class ClientArrowState {
    private static ArrowMode mode = ArrowMode.TARGET;
    /** Давно в прошлом — чтобы при входе в мир надпись режима не мигнула. */
    private static final long LONG_AGO = Long.MIN_VALUE / 2;
    private static long modeChangedAt = LONG_AGO;
    @Nullable
    private static OlegusArrowEntity activeArrow;
    private static boolean hasArrowItem;
    private static int trackedArrowId = -1;
    private static int trackedKills;
    private static long killedAt = LONG_AGO;

    private ClientArrowState() {
    }

    public static ArrowMode mode() {
        return mode;
    }

    public static void setMode(ArrowMode newMode) {
        if (newMode != mode) {
            mode = newMode;
            modeChangedAt = gameMillis();
        }
    }

    /** Миллисекунды с последней смены режима — для короткой подсветки надписи. */
    public static long millisSinceModeChange() {
        long since = gameMillis() - modeChangedAt;
        // В другом мире своё игровое время: отметка из прошлого мира может оказаться «в будущем».
        return since >= 0L ? since : Long.MAX_VALUE;
    }

    /**
     * Игровое время в миллисекундах — часы для анимаций HUD. Идёт вместе с тиками мира,
     * поэтому при замедлении игры командой /tick rate анимации замедляются вместе с ней.
     */
    public static long gameMillis() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return 0L;
        }
        return Math.round((client.world.getTime() + client.getRenderTickCounter().getTickDelta(false)) * 50.0);
    }

    /** Миллисекунды с последней победы стрелы — счётчик на миг подсвечивается. */
    public static long millisSinceKill() {
        long since = gameMillis() - killedAt;
        return since >= 0L ? since : Long.MAX_VALUE;
    }

    @Nullable
    public static OlegusArrowEntity activeArrow() {
        return activeArrow;
    }

    public static boolean hasArrowItem() {
        return hasArrowItem;
    }

    public static void tick(MinecraftClient client) {
        activeArrow = null;
        hasArrowItem = false;
        if (client.player == null || client.world == null) {
            return;
        }
        UUID self = client.player.getUuid();
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof OlegusArrowEntity arrow && arrow.isOwnedBy(self)
                    && (activeArrow == null || arrow.getSyncedLife() > activeArrow.getSyncedLife())) {
                activeArrow = arrow;
            }
        }
        trackKills();
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(ModItems.OLEGUS_ARROW)) {
                hasArrowItem = true;
                break;
            }
        }
    }

    private static void trackKills() {
        if (activeArrow == null) {
            trackedArrowId = -1;
            return;
        }
        int kills = activeArrow.getKills();
        if (activeArrow.getId() != trackedArrowId) {
            trackedArrowId = activeArrow.getId();
        } else if (kills > trackedKills) {
            killedAt = gameMillis();
        }
        trackedKills = kills;
    }

    public static void reset() {
        mode = ArrowMode.TARGET;
        modeChangedAt = LONG_AGO;
        killedAt = LONG_AGO;
        trackedArrowId = -1;
        activeArrow = null;
        hasArrowItem = false;
    }
}
