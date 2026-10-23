package com.olegus.arrow;

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
}
