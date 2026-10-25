package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import com.andryushka.arrow.recipe.TrailDyeRecipe;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialRecipeSerializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/** Особый рецепт окраски следа (data/andryushka_arrow/recipe/trail_dye.json). */
public final class ModRecipes {
    public static final RecipeSerializer<TrailDyeRecipe> TRAIL_DYE = Registry.register(Registries.RECIPE_SERIALIZER,
            AndryushkaArrowMod.id("trail_dye"), new SpecialRecipeSerializer<>(TrailDyeRecipe::new));

    private ModRecipes() {
    }

    public static void init() {
        // Сериализатор регистрируется при загрузке класса.
    }
}
