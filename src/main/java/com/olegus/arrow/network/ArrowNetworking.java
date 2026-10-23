package com.olegus.arrow.network;

import com.olegus.arrow.ArrowControl;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ArrowNetworking {
    private ArrowNetworking() {
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(ArrowCommandPayload.ID, ArrowCommandPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ModeSyncPayload.ID, ModeSyncPayload.CODEC);

        // Обработчик вызывается в потоке сервера, так что с миром можно работать напрямую.
        ServerPlayNetworking.registerGlobalReceiver(ArrowCommandPayload.ID,
                (payload, context) -> ArrowControl.handle(context.player(), payload.command()));
    }
}
