package com.andryushka.arrow;

import org.joml.Vector3f;

/** Цвета радужного следа и интерфейса. */
public final class Rainbow {
    private Rainbow() {
    }

    /** HSV → 0xRRGGBB; оттенок зацикливается, так что можно передавать любое число. */
    public static int hsvToRgb(float hue, float saturation, float value) {
        float h = (hue - (float) Math.floor(hue)) * 6.0F;
        int sector = (int) h;
        float f = h - sector;
        float p = value * (1.0F - saturation);
        float q = value * (1.0F - f * saturation);
        float t = value * (1.0F - (1.0F - f) * saturation);
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return (Math.round(r * 255.0F) << 16) | (Math.round(g * 255.0F) << 8) | Math.round(b * 255.0F);
    }

    /** 0xRRGGBB → цвет для частиц: каналы от 0 до 1. */
    public static Vector3f toVector(int rgb) {
        return new Vector3f(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
    }
}
