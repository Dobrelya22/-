package com.andryushka.arrow.client;

import com.andryushka.arrow.registry.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

/**
 * Драйвовый рок, пока у стрелы идёт серия побед (tools/rock_sounds.py): барабаны, две гитары
 * по сторонам и бас. Играет только владельцу и не зависит от расстояния до стрелы. Вступает
 * быстро, а когда победы прекращаются, плавно стихает.
 */
public class ComboRockSound extends MovingSoundInstance {
    private static final float MAX_VOLUME = 0.4F;
    private static final float FADE_IN_PER_TICK = 0.25F;
    private static final float FADE_OUT_PER_TICK = 1.0F / 30.0F;

    /** Насколько рок сейчас слышен: от 0 до 1. */
    private float level;

    public ComboRockSound() {
        super(ModSounds.COMBO_ROCK, SoundCategory.PLAYERS, SoundInstance.createRandom());
        this.repeat = true;
        this.repeatDelay = 0;
        this.relative = true;
        this.attenuationType = SoundInstance.AttenuationType.NONE;
        this.volume = 0.0F;
    }

    public float level() {
        return this.level;
    }

    // Начинается с тишины и сам набирает громкость — без этого игра не запустила бы беззвучный звук.
    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }

    @Override
    public void tick() {
        boolean rocking = ClientArrowState.isRocking();
        this.level = rocking
                ? Math.min(1.0F, this.level + FADE_IN_PER_TICK)
                : Math.max(0.0F, this.level - FADE_OUT_PER_TICK);
        this.volume = MAX_VOLUME * this.level;
        if (!rocking && this.level <= 0.0F) {
            this.setDone();
        }
    }
}
