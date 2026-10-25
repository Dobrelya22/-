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
    /** Гитара при ударе по мобу: один из восьми приёмов. */
    public static final SoundEvent STRIKE = register("strike");
    /** Драйвовый рок, пока идёт серия побед (зацикленный, только владельцу). */
    public static final SoundEvent COMBO_ROCK = register("combo_rock");
    /** Две соло-гитары поверх рока с четвёртой победы серии. */
    public static final SoundEvent COMBO_ROCK_LEAD = register("combo_rock_lead");
    /** Пауэр-метал на «неудержимую» серию. */
    public static final SoundEvent COMBO_EPIC = register("combo_epic");
    /** Финальный аккорд, когда серия кончилась. */
    public static final SoundEvent COMBO_END = register("combo_end");
    /** Гитарные фразы на вторую — пятую победу серии. */
    public static final SoundEvent COMBO_LICK_2 = register("combo_lick2");
    public static final SoundEvent COMBO_LICK_3 = register("combo_lick3");
    public static final SoundEvent COMBO_LICK_4 = register("combo_lick4");
    public static final SoundEvent COMBO_LICK_5 = register("combo_lick5");

    private ModSounds() {
    }

    private static SoundEvent register(String name) {
        Identifier id = AndryushkaArrowMod.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {
    }
}
