package com.andryushka.arrow;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.DyeColor;
import net.minecraft.util.StringIdentifiable;

/**
 * Окраска следа стрелы. Радуга перебирает все цвета, остальные стили переливаются внутри
 * своей гаммы. Меняется на верстаке: стрела + краситель.
 */
public enum ArrowTrail implements StringIdentifiable {
    RAINBOW("rainbow", 0.0F, 1.0F, 0.85F),
    FIRE("fire", 0.0F, 0.12F, 0.95F),
    ICE("ice", 0.48F, 0.14F, 0.5F),
    TOXIC("toxic", 0.2F, 0.16F, 0.9F),
    VOID("void", 0.72F, 0.16F, 0.8F),
    GOLD("gold", 0.1F, 0.06F, 0.75F);

    public static final Codec<ArrowTrail> CODEC = StringIdentifiable.createCodec(ArrowTrail::values);
    public static final PacketCodec<ByteBuf, ArrowTrail> PACKET_CODEC =
            PacketCodecs.indexed(ArrowTrail::byIndex, ArrowTrail::ordinal);

    private final String id;
    private final float hueStart;
    private final float hueSpan;
    private final float saturation;

    ArrowTrail(String id, float hueStart, float hueSpan, float saturation) {
        this.id = id;
        this.hueStart = hueStart;
        this.hueSpan = hueSpan;
        this.saturation = saturation;
    }

    @Override
    public String asString() {
        return this.id;
    }

    public String translationKey() {
        return "trail.andryushka_arrow." + this.id;
    }

    /** Цвет 0xRRGGBB в точке {@code phase}: фаза бежит вдоль следа и со временем. */
    public int color(float phase) {
        return this.color(phase, this.saturation);
    }

    public int color(float phase, float saturation) {
        if (this == RAINBOW) {
            return Rainbow.hsvToRgb(phase, saturation, 1.0F);
        }
        // Треугольная волна: оттенок ходит туда-обратно внутри гаммы без скачка на краю.
        float wave = phase - (float) Math.floor(phase);
        float within = wave < 0.5F ? wave * 2.0F : 2.0F - wave * 2.0F;
        return Rainbow.hsvToRgb(this.hueStart + this.hueSpan * within, saturation, 1.0F);
    }

    public static ArrowTrail byIndex(int index) {
        ArrowTrail[] trails = values();
        return index >= 0 && index < trails.length ? trails[index] : RAINBOW;
    }

    /** Какой след даёт краситель. */
    public static ArrowTrail forDye(DyeColor dye) {
        return switch (dye) {
            case RED, ORANGE -> FIRE;
            case YELLOW -> GOLD;
            case LIME, GREEN -> TOXIC;
            case CYAN, LIGHT_BLUE, BLUE -> ICE;
            case PURPLE, MAGENTA, PINK, BLACK, GRAY -> VOID;
            case WHITE, LIGHT_GRAY, BROWN -> RAINBOW;
        };
    }
}
