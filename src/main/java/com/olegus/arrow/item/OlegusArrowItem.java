package com.olegus.arrow.item;

import com.olegus.arrow.ArrowMode;
import com.olegus.arrow.entity.OlegusArrowEntity;
import com.olegus.arrow.entity.TargetRules;
import com.olegus.arrow.network.ArrowCommand;
import com.olegus.arrow.registry.ModAttachments;
import java.util.List;
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

/** Предмет «Стрела Олегуса»: по ПКМ превращается в летящую стрелу. */
public class OlegusArrowItem extends Item {
    public OlegusArrowItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world instanceof ServerWorld serverWorld && user instanceof ServerPlayerEntity player) {
            ArrowMode mode = ModAttachments.getMode(player);
            MobEntity target = mode == ArrowMode.TARGET
                    ? TargetRules.findLookTarget(player, OlegusArrowEntity.SEARCH_RADIUS)
                    : null;
            boolean creative = player.getAbilities().creativeMode;
            // В творческом режиме предмет остаётся в руке, а стрела потом просто растворяется.
            OlegusArrowEntity.launch(serverWorld, player, creative ? ItemStack.EMPTY : stack.copyWithCount(1), mode, target);
            if (mode == ArrowMode.TARGET && target == null) {
                player.sendMessage(Text.translatable("message.olegus_arrow.waiting_for_target",
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

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("tooltip.olegus_arrow.launch").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.olegus_arrow.mode",
                Text.keybind(ArrowCommand.TOGGLE_MODE.keyTranslation())).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.olegus_arrow.designate",
                Text.keybind(ArrowCommand.DESIGNATE.keyTranslation())).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.olegus_arrow.whistle",
                Text.keybind(ArrowCommand.WHISTLE.keyTranslation())).formatted(Formatting.GRAY));
    }
}
