package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSounds {
    /** Свист владельца, которым он отзывает стрелу. */
    public static final SoundEvent WHISTLE = register("whistle");
    /** Ответ стрелы на свист. */
    public static final SoundEvent RESPOND = register("respond");
    /** Взлёт стрелы. */
    public static final SoundEvent LAUNCH = register("launch");
    /** Бас-рифф, который тянется за стрелой в полёте (зацикленный). */
    public static final SoundEvent FLIGHT = register("flight");
    /** Гитарный «лязг» при ударе по мобу. */
    public static final SoundEvent STRIKE = register("strike");
    /** Драйвовый рок, пока идёт серия побед (зацикленный, только владельцу). */
    public static final SoundEvent COMBO_ROCK = register("combo_rock");
    /** Визг соло-гитары на каждую победу серии. */
    public static final SoundEvent COMBO_LICK = register("combo_lick");

    private ModSounds() {
    }

    private static SoundEvent register(String name) {
        Identifier id = AndryushkaArrowMod.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {
    }
}
