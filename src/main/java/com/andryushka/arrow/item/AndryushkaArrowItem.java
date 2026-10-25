package com.andryushka.arrow.item;

import com.andryushka.arrow.ArrowLevel;
import com.andryushka.arrow.ArrowMode;
import com.andryushka.arrow.ArrowTrail;
import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.entity.TargetRules;
import com.andryushka.arrow.network.ArrowCommand;
import com.andryushka.arrow.registry.ModAttachments;
import com.andryushka.arrow.registry.ModComponents;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Предмет «Стрела Андрюшки»: по ПКМ превращается в летящую стрелу. */
public class AndryushkaArrowItem extends Item {
    public AndryushkaArrowItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world instanceof ServerWorld serverWorld && user instanceof ServerPlayerEntity player) {
            ArrowMode mode = ModAttachments.getMode(player);
            MobEntity target = mode == ArrowMode.TARGET
                    ? TargetRules.findLookTarget(player, AndryushkaArrowEntity.SEARCH_RADIUS)
                    : null;
            boolean creative = player.getAbilities().creativeMode;
            if (!stack.contains(ModComponents.ARROW_ID)) {
                stack.set(ModComponents.ARROW_ID, UUID.randomUUID());
            }
            // В творческом режиме предмет остаётся в руке, а летит его копия: она не вернётся,
            // но её победы потом запишутся в этот предмет.
            AndryushkaArrowEntity.launch(serverWorld, player, stack.copyWithCount(1), mode, target, !creative);
            if (mode == ArrowMode.TARGET && target == null) {
                player.sendMessage(Text.translatable("message.andryushka_arrow.waiting_for_target",
                        Text.keybind(ArrowCommand.DESIGNATE.keyTranslation())), true);
            }
            player.incrementStat(Stats.USED.getOrCreateStat(this));
            player.getItemCooldownManager().set(this, 20);
            if (!creative) {
                stack.decrement(1);
            }
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    /** Начиная со второго уровня к названию добавляется римская цифра: «Стрела Андрюшки III». */
    @Override
    public Text getName(ItemStack stack) {
        int level = ArrowLevel.of(stack.getOrDefault(ModComponents.KILLS, 0));
        Text name = super.getName(stack);
        return level > 1 ? Text.empty().append(name).append(" " + ArrowLevel.roman(level)) : name;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        int kills = stack.getOrDefault(ModComponents.KILLS, 0);
        int level = ArrowLevel.of(kills);
        tooltip.add(Text.translatable("tooltip.andryushka_arrow.level", ArrowLevel.roman(level), kills)
                .formatted(Formatting.GOLD));
        if (level < ArrowLevel.MAX) {
            tooltip.add(Text.translatable("tooltip.andryushka_arrow.next_level", ArrowLevel.roman(level + 1),
                    ArrowLevel.killsFor(level + 1) - kills).formatted(Formatting.DARK_GRAY));
        }
        if (level > 1) {
            tooltip.add(Text.translatable("tooltip.andryushka_arrow.bonus",
                    Math.round((ArrowLevel.speedFactor(level) - 1.0) * 100), ArrowLevel.extraSeconds(level),
                    Math.round(ArrowLevel.extraDamage(level))).formatted(Formatting.YELLOW));
        }
        ArrowTrail trail = stack.getOrDefault(ModComponents.TRAIL, ArrowTrail.RAINBOW);
        tooltip.add(Text.translatable("tooltip.andryushka_arrow.trail", Text.translatable(trail.translationKey()))
                .withColor(trail.color(0.25F)));
        tooltip.add(Text.translatable("tooltip.andryushka_arrow.dye").formatted(Formatting.DARK_GRAY));
        tooltip.add(Text.translatable("tooltip.andryushka_arrow.launch").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.andryushka_arrow.mode",
                Text.keybind(ArrowCommand.TOGGLE_MODE.keyTranslation())).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.andryushka_arrow.designate",
                Text.keybind(ArrowCommand.DESIGNATE.keyTranslation())).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.andryushka_arrow.whistle",
                Text.keybind(ArrowCommand.WHISTLE.keyTranslation())).formatted(Formatting.GRAY));
    }
}
