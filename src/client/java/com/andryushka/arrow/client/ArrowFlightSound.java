package com.andryushka.arrow.client;

import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.registry.ModSounds;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;

/**
 * Стрела в полёте поёт флейтой (tools/flute_flight.py): звук следует за ней, громче на скорости
 * и тише, когда она кружит. Высоту почти не трогаем, чтобы мелодия не «плыла»; только в толпе,
 * разогнавшись, стрела звучит чуть выше.
 */
public class ArrowFlightSound extends MovingSoundInstance {
    private final AndryushkaArrowEntity arrow;
    private double lastX;
    private double lastY;
    private double lastZ;

    public ArrowFlightSound(AndryushkaArrowEntity arrow) {
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
        this.volume = MathHelper.clamp(0.18F + speed * 0.45F, 0.12F, 0.6F);
        this.pitch = 1.0F + (this.arrow.getBoost() - 1.0F) * 0.06F;
    }
}
