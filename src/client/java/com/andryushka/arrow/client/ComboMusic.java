package com.andryushka.arrow.client;

import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.registry.ModSounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Музыка серии побед — её слышит только владелец стрелы (tools/rock_sounds.py). Со второй победы
 * подряд вступает рок, с четвёртой поверх него играют две соло-гитары, на «неудержимой» (шестая и
 * дальше) врубается пауэр-метал. На каждую ступень до шестой — своя гитарная фраза, а когда серия
 * кончилась — финальный аккорд.
 */
public final class ComboMusic {
    private static final int LEAD_FROM = 4;
    private static final int EPIC_FROM = 6;
    /** Серия держится окно серии после последней победы и ещё секунду. */
    private static final long HOLD_MILLIS = AndryushkaArrowEntity.COMBO_WINDOW_TICKS * 50L + 1000L;
    private static final long NEVER = Long.MIN_VALUE / 2;
    /** Фразы гитары на «двойную», «тройную», «четверную» и «мега-серию». */
    private static final SoundEvent[] LICKS = {ModSounds.COMBO_LICK_2, ModSounds.COMBO_LICK_3, ModSounds.COMBO_LICK_4,
            ModSounds.COMBO_LICK_5};
    private static long rockingUntil = NEVER;
    private static int peak;
    @Nullable
    private static ComboTrack rock;
    @Nullable
    private static ComboTrack lead;
    @Nullable
    private static ComboTrack epic;
    @Nullable
    private static ComboTrack lick;

    private ComboMusic() {
    }

    /** Новая победа серии: combo — её номер, начиная со второй. */
    public static void onCombo(MinecraftClient client, int combo) {
        SoundManager sounds = client.getSoundManager();
        rockingUntil = ClientArrowState.gameMillis() + HOLD_MILLIS;
        peak = Math.max(peak, combo);
        // Новая фраза обрывает прежнюю, чтобы они не наслаивались.
        if (lick != null) {
            sounds.stop(lick);
            lick = null;
        }
        if (combo >= EPIC_FROM) {
            if (!playing(epic)) {
                epic = play(sounds, new ComboTrack(ModSounds.COMBO_EPIC, true, 0.55F, 1.0F, 0.1F, 1.0F));
                finish(rock);
                finish(lead);
            }
            return;
        }
        if (!playing(rock)) {
            // Рок и соло-гитары начинаются вместе и одной длины — так они не разъезжаются.
            rock = play(sounds, new ComboTrack(ModSounds.COMBO_ROCK, true, 0.4F, 0.25F, 0.1F, 1.0F));
            lead = play(sounds, new ComboTrack(ModSounds.COMBO_ROCK_LEAD, true, 0.38F, 0.1F, 0.1F, 0.0F));
        }
        lick = play(sounds, new ComboTrack(LICKS[combo - 2], false, 0.6F, 1.0F, 1.0F, 1.0F));
    }

    public static void tick(MinecraftClient client) {
        if (peak == 0) {
            return;
        }
        if (ClientArrowState.gameMillis() < rockingUntil) {
            if (lead != null && peak >= LEAD_FROM) {
                lead.setTarget(1.0F);
            }
            return;
        }
        // Серия кончилась: всё стихает под финальный аккорд.
        finish(rock);
        finish(lead);
        finish(epic);
        client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.COMBO_END, 1.0F, 0.6F));
        peak = 0;
    }

    /** Насколько громко сейчас музыка серии (0–1): бас полёта на это время уходит. */
    public static float level() {
        float level = 0.0F;
        for (ComboTrack track : new ComboTrack[] {rock, lead, epic}) {
            if (track != null) {
                level = Math.max(level, track.level());
            }
        }
        return level;
    }

    public static void reset() {
        rockingUntil = NEVER;
        peak = 0;
        rock = lead = epic = lick = null;
    }

    private static ComboTrack play(SoundManager sounds, ComboTrack track) {
        sounds.play(track);
        return track;
    }

    private static boolean playing(@Nullable ComboTrack track) {
        return track != null && track.active();
    }

    private static void finish(@Nullable ComboTrack track) {
        if (track != null) {
            track.finish();
        }
    }
}
