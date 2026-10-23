package com.olegus.arrow.registry;

import com.olegus.arrow.OlegusArrowMod;
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
    /** Свист рассекаемого воздуха, который тянется за стрелой в полёте (зацикленный). */
    public static final SoundEvent FLIGHT = register("flight");

    private ModSounds() {
    }

    private static SoundEvent register(String name) {
        Identifier id = OlegusArrowMod.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() {
    }
}
