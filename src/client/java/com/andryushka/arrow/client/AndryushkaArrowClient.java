package com.andryushka.arrow.client;

import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.network.ModeSyncPayload;
import com.andryushka.arrow.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

public class AndryushkaArrowClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(AndryushkaArrowRenderer.MODEL_LAYER,
                AndryushkaArrowRenderer::getTexturedModelData);
        EntityRendererRegistry.register(ModEntities.ANDRYUSHKA_ARROW, AndryushkaArrowRenderer::new);
        ArrowKeyBindings.register();

        ClientPlayNetworking.registerGlobalReceiver(ModeSyncPayload.ID,
                (payload, context) -> ClientArrowState.setMode(payload.mode()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientArrowState.reset());

        // Как только стрела появилась в мире, за ней начинает тянуться свист полёта.
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof AndryushkaArrowEntity arrow) {
                MinecraftClient.getInstance().getSoundManager().play(new ArrowFlightSound(arrow));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ArrowKeyBindings.tick(client);
            ClientArrowState.tick(client);
        });
        HudRenderCallback.EVENT.register(ArrowHud::render);
    }
}
