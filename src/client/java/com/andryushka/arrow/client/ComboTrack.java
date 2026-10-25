package com.andryushka.arrow.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

/**
 * Дорожка музыки серии (tools/rock_sounds.py): играет только владельцу, не зависит от расстояния
 * и плавно идёт к заданной громкости. Её ведёт {@link ComboMusic}.
 */
public class ComboTrack extends MovingSoundInstance {
    private final float maxVolume;
    private final float fadeIn;
    private final float fadeOut;
    /** Насколько дорожка сейчас слышна: от 0 до 1. */
    private float level;
    private float target;
    private boolean finished;

    public ComboTrack(SoundEvent sound, boolean loop, float maxVolume, float fadeIn, float fadeOut, float target) {
        super(sound, SoundCategory.PLAYERS, SoundInstance.createRandom());
        this.repeat = loop;
        this.repeatDelay = 0;
        this.relative = true;
        this.attenuationType = SoundInstance.AttenuationType.NONE;
        this.maxVolume = maxVolume;
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
        this.target = target;
        this.level = Math.min(target, fadeIn);
        this.volume = maxVolume * this.level;
    }

    public float level() {
        return this.isDone() ? 0.0F : this.level;
    }

    /** Играет и не стихает насовсем. */
    public boolean active() {
        return !this.isDone() && !this.finished;
    }

    public void setTarget(float target) {
        this.target = target;
    }

    /** Стихнуть и закончиться. */
    public void finish() {
        this.target = 0.0F;
        this.finished = true;
    }

    // Дорожка может начаться беззвучной и набрать громкость потом — без этого игра её бы не запустила.
    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }

    @Override
    public void tick() {
        this.level = this.level < this.target
                ? Math.min(this.target, this.level + this.fadeIn)
                : Math.max(this.target, this.level - this.fadeOut);
        this.volume = this.maxVolume * this.level;
        if (this.finished && this.level <= 0.0F) {
            this.setDone();
        }
    }
}
