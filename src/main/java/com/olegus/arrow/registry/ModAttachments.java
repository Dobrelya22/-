package com.olegus.arrow.registry;

import com.olegus.arrow.ArrowMode;
import com.olegus.arrow.OlegusArrowMod;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.player.PlayerEntity;

/** Выбранный режим стрелы хранится в данных игрока и переживает смерть и перезаход. */
public final class ModAttachments {
    public static final AttachmentType<ArrowMode> ARROW_MODE = AttachmentRegistry.<ArrowMode>builder()
            .persistent(ArrowMode.CODEC)
            .copyOnDeath()
            .initializer(() -> ArrowMode.TARGET)
            .buildAndRegister(OlegusArrowMod.id("arrow_mode"));

    private ModAttachments() {
    }

    public static ArrowMode getMode(PlayerEntity player) {
        return player.getAttachedOrCreate(ARROW_MODE);
    }

    public static void setMode(PlayerEntity player, ArrowMode mode) {
        player.setAttached(ARROW_MODE, mode);
    }

    public static void init() {
    }
}
