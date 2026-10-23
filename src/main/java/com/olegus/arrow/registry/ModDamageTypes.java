package com.olegus.arrow.registry;

import com.olegus.arrow.OlegusArrowMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Свой тип урона (data/olegus_arrow/damage_type). Он намеренно не входит в тег is_projectile:
 * иначе эндермены уворачивались бы телепортом, а это волшебная стрела — от неё не уйти.
 */
public final class ModDamageTypes {
    public static final RegistryKey<DamageType> OLEGUS_ARROW =
            RegistryKey.of(RegistryKeys.DAMAGE_TYPE, OlegusArrowMod.id("olegus_arrow"));

    private ModDamageTypes() {
    }

    public static DamageSource arrow(World world, Entity arrow, @Nullable Entity attacker) {
        return new DamageSource(
                world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(OLEGUS_ARROW), arrow, attacker);
    }
}
