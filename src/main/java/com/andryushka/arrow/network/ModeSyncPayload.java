package com.andryushka.arrow.network;

import com.andryushka.arrow.ArrowMode;
import com.andryushka.arrow.AndryushkaArrowMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** Сервер → клиент: текущий режим стрелы для надписи над полоской здоровья. */
public record ModeSyncPayload(ArrowMode mode) implements CustomPayload {
    public static final CustomPayload.Id<ModeSyncPayload> ID = new CustomPayload.Id<>(AndryushkaArrowMod.id("mode_sync"));
    public static final PacketCodec<ByteBuf, ModeSyncPayload> CODEC = PacketCodecs.VAR_INT.xmap(
            index -> new ModeSyncPayload(ArrowMode.byIndex(index)),
            payload -> payload.mode().ordinal());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
