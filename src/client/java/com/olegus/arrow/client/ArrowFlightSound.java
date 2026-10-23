package com.olegus.arrow.client;

import com.olegus.arrow.entity.OlegusArrowEntity;
import com.olegus.arrow.registry.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;

/** Свист стрелы в полёте: следует за ней, громче и выше на скорости, затихает при зависании. */
public class ArrowFlightSound extends MovingSoundInstance {
    private final OlegusArrowEntity arrow;
    private double lastX;
    private double lastY;
    private double lastZ;

    public ArrowFlightSound(OlegusArrowEntity arrow) {
        super(ModSounds.FLIGHT, SoundCategory.NEUTRAL, SoundInstance.createRandom());
        this.arrow = arrow;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.05F;
        this.x = this.lastX = arrow.getX();
        this.y = this.lastY = arrow.getY();
        this.z = this.lastZ = arrow.getZ();
    }

    @Override
    public void tick() {
        if (this.arrow.isRemoved()) {
            this.setDone();
            return;
        }
        double dx = this.arrow.getX() - this.lastX;
        double dy = this.arrow.getY() - this.lastY;
        double dz = this.arrow.getZ() - this.lastZ;
        this.lastX = this.arrow.getX();
        this.lastY = this.arrow.getY();
        this.lastZ = this.arrow.getZ();
        float speed = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        this.x = this.arrow.getX();
        this.y = this.arrow.getY();
        this.z = this.arrow.getZ();
        this.volume = MathHelper.clamp(0.12F + speed * 0.6F, 0.05F, 0.8F);
        this.pitch = MathHelper.clamp(0.75F + speed * 0.4F + (this.arrow.getBoost() - 1.0F) * 0.2F, 0.5F, 2.0F);
    }
}
