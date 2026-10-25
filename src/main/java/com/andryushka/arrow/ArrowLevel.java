package com.andryushka.arrow;

/**
 * Уровень стрелы по числу побед за всё время. Каждый уровень после первого добавляет 8%
 * скорости, 5 секунд полёта и единицу урона (половину сердечка).
 */
public final class ArrowLevel {
    public static final int MAX = 5;
    /** Сколько побед нужно для уровня: индекс — уровень минус один. */
    private static final int[] KILLS_FOR_LEVEL = {0, 15, 40, 80, 150};
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V"};

    private ArrowLevel() {
    }

    public static int of(int kills) {
        int level = 1;
        while (level < MAX && kills >= KILLS_FOR_LEVEL[level]) {
            level++;
        }
        return level;
    }

    /** Сколько побед нужно для уровня {@code level}. */
    public static int killsFor(int level) {
        return KILLS_FOR_LEVEL[Math.max(1, Math.min(MAX, level)) - 1];
    }

    public static String roman(int level) {
        return ROMAN[Math.max(1, Math.min(MAX, level)) - 1];
    }

    public static double speedFactor(int level) {
        return 1.0 + 0.08 * (level - 1);
    }

    public static int extraSeconds(int level) {
        return 5 * (level - 1);
    }

    public static float extraDamage(int level) {
        return level - 1;
    }
}
