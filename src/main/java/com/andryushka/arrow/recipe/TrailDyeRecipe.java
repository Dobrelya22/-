package com.andryushka.arrow.recipe;

import com.andryushka.arrow.ArrowTrail;
import com.andryushka.arrow.registry.ModComponents;
import com.andryushka.arrow.registry.ModItems;
import com.andryushka.arrow.registry.ModRecipes;
import net.minecraft.item.DyeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Стрела + краситель на верстаке — новый цвет следа. Победы и уровень стрелы сохраняются,
 * поэтому это особый рецепт, а не обычный JSON.
 */
public class TrailDyeRecipe extends SpecialCraftingRecipe {
    public TrailDyeRecipe(CraftingRecipeCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingRecipeInput input, World world) {
        return find(input) != null;
    }

    @Override
    public ItemStack craft(CraftingRecipeInput input, RegistryWrapper.WrapperLookup lookup) {
        Match match = find(input);
        if (match == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = match.arrow().copyWithCount(1);
        result.set(ModComponents.TRAIL, ArrowTrail.forDye(match.dye().getColor()));
        return result;
    }

    @Override
    public boolean fits(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.TRAIL_DYE;
    }

    private record Match(ItemStack arrow, DyeItem dye) {
    }

    /** Ровно одна стрела и ровно один краситель, больше ничего. */
    @Nullable
    private static Match find(CraftingRecipeInput input) {
        ItemStack arrow = null;
        DyeItem dye = null;
        for (int slot = 0; slot < input.getSize(); slot++) {
            ItemStack stack = input.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(ModItems.ANDRYUSHKA_ARROW) && arrow == null) {
                arrow = stack;
            } else if (stack.getItem() instanceof DyeItem dyeItem && dye == null) {
                dye = dyeItem;
            } else {
                return null;
            }
        }
        return arrow != null && dye != null ? new Match(arrow, dye) : null;
    }
}
