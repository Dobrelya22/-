package com.andryushka.arrow.network;

import com.andryushka.arrow.AndryushkaArrowMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** Клиент → сервер: нажата клавиша управления стрелой. */
public record ArrowCommandPayload(ArrowCommand command) implements CustomPayload {
    public static final CustomPayload.Id<ArrowCommandPayload> ID = new CustomPayload.Id<>(AndryushkaArrowMod.id("command"));
    public static final PacketCodec<ByteBuf, ArrowCommandPayload> CODEC = PacketCodecs.VAR_INT.xmap(
            index -> new ArrowCommandPayload(ArrowCommand.byIndex(index)),
            payload -> payload.command().ordinal());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
