package com.andryushka.arrow;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringIdentifiable;

/** Режим охоты стрелы. Хранится у игрока и переключается клавишей. */
public enum ArrowMode implements StringIdentifiable {
    /** Атакует только выбранную цель (моба под прицелом). */
    TARGET("target"),
    /** По очереди атакует всех мобов в радиусе 50 блоков вокруг владельца. */
    ALL("all"),
    /** Кружит у владельца и сбивает тех, кто на него напал, целится в него или подобрался близко. */
    GUARD("guard");

    public static final Codec<ArrowMode> CODEC = StringIdentifiable.createCodec(ArrowMode::values);

    private final String id;

    ArrowMode(String id) {
        this.id = id;
    }

    @Override
    public String asString() {
        return this.id;
    }

    public ArrowMode next() {
        ArrowMode[] modes = values();
        return modes[(this.ordinal() + 1) % modes.length];
    }

    public String translationKey() {
        return "mode.andryushka_arrow." + this.id;
    }

    public static ArrowMode byId(String id) {
        for (ArrowMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return TARGET;
    }

    public static ArrowMode byIndex(int index) {
        ArrowMode[] modes = values();
        return index >= 0 && index < modes.length ? modes[index] : TARGET;
    }
}
