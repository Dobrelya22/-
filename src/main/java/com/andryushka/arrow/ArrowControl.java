package com.andryushka.arrow;

import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.entity.TargetRules;
import com.andryushka.arrow.network.ArrowCommand;
import com.andryushka.arrow.network.ModeSyncPayload;
import com.andryushka.arrow.registry.ModAdvancements;
import com.andryushka.arrow.registry.ModAttachments;
import com.andryushka.arrow.registry.ModSounds;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

/** Серверная сторона управления: режим, указание цели и свист. */
public final class ArrowControl {
    /** Стрелы владельца ищутся в этом радиусе (они не улетают дальше 96 блоков от него). */
    private static final double ARROW_SEARCH_RADIUS = 128.0;
    private static final long WHISTLE_COOLDOWN_TICKS = 10L;
    private static final Map<UUID, Long> LAST_WHISTLE = new HashMap<>();

    private ArrowControl() {
    }

    public static void init() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncMode(handler.getPlayer()));
        // Ударили моба сами — свободная стрела приходит на помощь.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer && entity instanceof MobEntity mob
                    && TargetRules.isValidTarget(mob, serverPlayer, AndryushkaArrowEntity.SEARCH_RADIUS)) {
                for (AndryushkaArrowEntity arrow : findArrows(serverPlayer)) {
                    arrow.assist(mob);
                }
            }
            return ActionResult.PASS;
        });
        // Игрок вышел — стрела возвращается в инвентарь до сохранения его данных, чтобы не потеряться.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            LAST_WHISTLE.remove(player.getUuid());
            for (AndryushkaArrowEntity arrow : findArrows(player)) {
                arrow.returnToOwner(player, false);
            }
        });
    }

    public static void handle(ServerPlayerEntity player, ArrowCommand command) {
        switch (command) {
            case TOGGLE_MODE -> toggleMode(player);
            case DESIGNATE -> designate(player);
            case WHISTLE -> whistle(player);
        }
    }

    private static void toggleMode(ServerPlayerEntity player) {
        ArrowMode mode = ModAttachments.getMode(player).next();
        ModAttachments.setMode(player, mode);
        syncMode(player);
        for (AndryushkaArrowEntity arrow : findArrows(player)) {
            arrow.setMode(mode);
        }
    }

    private static void designate(ServerPlayerEntity player) {
        List<AndryushkaArrowEntity> arrows = findArrows(player);
        if (arrows.isEmpty()) {
            player.sendMessage(Text.translatable("message.andryushka_arrow.no_arrow"), true);
            return;
        }
        MobEntity target = TargetRules.findLookTarget(player, AndryushkaArrowEntity.SEARCH_RADIUS);
        if (target == null) {
            player.sendMessage(Text.translatable("message.andryushka_arrow.no_target"), true);
            return;
        }
        boolean accepted = false;
        for (AndryushkaArrowEntity arrow : arrows) {
            accepted |= arrow.designate(target);
        }
        if (accepted) {
            player.sendMessage(Text.translatable("message.andryushka_arrow.target_set", target.getDisplayName()), true);
        }
    }

    private static void whistle(ServerPlayerEntity player) {
        long now = player.getWorld().getTime();
        Long last = LAST_WHISTLE.get(player.getUuid());
        if (last != null && now >= last && now - last < WHISTLE_COOLDOWN_TICKS) {
            return;
        }
        LAST_WHISTLE.put(player.getUuid(), now);
        player.getWorld().playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModSounds.WHISTLE,
                SoundCategory.PLAYERS, 2.0F, 0.95F + player.getRandom().nextFloat() * 0.1F);
        boolean recalled = false;
        for (AndryushkaArrowEntity arrow : findArrows(player)) {
            recalled |= arrow.recall();
        }
        if (recalled) {
            ModAdvancements.grant(player, ModAdvancements.WHISTLE);
        }
    }

    public static List<AndryushkaArrowEntity> findArrows(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        return player.getWorld().getEntitiesByClass(AndryushkaArrowEntity.class,
                player.getBoundingBox().expand(ARROW_SEARCH_RADIUS), arrow -> arrow.isOwnedBy(uuid));
    }

    public static void syncMode(ServerPlayerEntity player) {
        // Клиент без мода этот пакет не поймёт — тогда просто не шлём.
        if (ServerPlayNetworking.canSend(player, ModeSyncPayload.ID)) {
            ServerPlayNetworking.send(player, new ModeSyncPayload(ModAttachments.getMode(player)));
        }
    }
}
