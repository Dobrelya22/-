package com.olegus.arrow.client;

import com.olegus.arrow.network.ModeSyncPayload;
import com.olegus.arrow.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class OlegusArrowClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.OLEGUS_ARROW, OlegusArrowRenderer::new);
        ArrowKeyBindings.register();

        ClientPlayNetworking.registerGlobalReceiver(ModeSyncPayload.ID,
                (payload, context) -> ClientArrowState.setMode(payload.mode()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientArrowState.reset());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ArrowKeyBindings.tick(client);
            ClientArrowState.tick(client);
        });
        HudRenderCallback.EVENT.register(ArrowHud::render);
    }
}
