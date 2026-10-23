package com.olegus.arrow.client;

import com.olegus.arrow.ArrowMode;
import com.olegus.arrow.entity.OlegusArrowEntity;
import com.olegus.arrow.registry.ModItems;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/** Что клиент знает о своей стреле: режим, летящая стрела и есть ли она в инвентаре. */
public final class ClientArrowState {
    private static ArrowMode mode = ArrowMode.TARGET;
    private static long modeChangedAt;
    @Nullable
    private static OlegusArrowEntity activeArrow;
    private static boolean hasArrowItem;

    private ClientArrowState() {
    }

    public static ArrowMode mode() {
        return mode;
    }

    public static void setMode(ArrowMode newMode) {
        if (newMode != mode) {
            mode = newMode;
            modeChangedAt = Util.getMeasuringTimeMs();
        }
    }

    /** Миллисекунды с последней смены режима — для короткой подсветки надписи. */
    public static long millisSinceModeChange() {
        return Util.getMeasuringTimeMs() - modeChangedAt;
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
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(ModItems.OLEGUS_ARROW)) {
                hasArrowItem = true;
                break;
            }
        }
    }

    public static void reset() {
        mode = ArrowMode.TARGET;
        activeArrow = null;
        hasArrowItem = false;
    }
}
