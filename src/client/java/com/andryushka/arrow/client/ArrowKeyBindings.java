package com.andryushka.arrow.client;

import com.andryushka.arrow.ArrowMode;
import com.andryushka.arrow.network.ArrowCommand;
import com.andryushka.arrow.network.ArrowCommandPayload;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Клавиши управления стрелой; переназначаются в «Настройки → Управление». */
public final class ArrowKeyBindings {
    private static final String CATEGORY = "key.category.andryushka_arrow";

    public static final KeyBinding TOGGLE_MODE = create(ArrowCommand.TOGGLE_MODE, GLFW.GLFW_KEY_R);
    public static final KeyBinding DESIGNATE = create(ArrowCommand.DESIGNATE, GLFW.GLFW_KEY_V);
    public static final KeyBinding WHISTLE = create(ArrowCommand.WHISTLE, GLFW.GLFW_KEY_G);

    private ArrowKeyBindings() {
    }

    private static KeyBinding create(ArrowCommand command, int defaultKey) {
        return new KeyBinding(command.keyTranslation(), InputUtil.Type.KEYSYM, defaultKey, CATEGORY);
    }

    public static void register() {
        KeyBindingHelper.registerKeyBinding(TOGGLE_MODE);
        KeyBindingHelper.registerKeyBinding(DESIGNATE);
        KeyBindingHelper.registerKeyBinding(WHISTLE);
    }

    public static void tick(MinecraftClient client) {
        while (TOGGLE_MODE.wasPressed()) {
            if (send(client, ArrowCommand.TOGGLE_MODE)) {
                // Сразу показываем новый режим; сервер подтвердит его своим пакетом.
                ArrowMode mode = ClientArrowState.mode().next();
                ClientArrowState.setMode(mode);
                client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.6F));
                client.player.sendMessage(Text.translatable("message.andryushka_arrow.mode_set",
                        Text.translatable(mode.translationKey())), true);
            }
        }
        while (DESIGNATE.wasPressed()) {
            send(client, ArrowCommand.DESIGNATE);
        }
        while (WHISTLE.wasPressed()) {
            send(client, ArrowCommand.WHISTLE);
        }
    }

    private static boolean send(MinecraftClient client, ArrowCommand command) {
        if (client.player == null) {
            return false;
        }
        if (!ClientPlayNetworking.canSend(ArrowCommandPayload.ID)) {
            client.player.sendMessage(Text.translatable("message.andryushka_arrow.server_missing"), true);
            return false;
        }
        ClientPlayNetworking.send(new ArrowCommandPayload(command));
        return true;
    }
}
