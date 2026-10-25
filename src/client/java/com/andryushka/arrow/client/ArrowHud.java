package com.andryushka.arrow.client;

import com.andryushka.arrow.ArrowLevel;
import com.andryushka.arrow.ArrowMode;
import com.andryushka.arrow.Rainbow;
import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.network.ArrowCommand;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

/** Строка над полоской здоровья: режим стрелы, а пока она летит — её состояние и оставшееся время. */
public final class ArrowHud {
    private static final String ICON = "➶";
    private static final long MODE_FLASH_MILLIS = 600L;
    private static final long KILL_FLASH_MILLIS = 500L;
    private static final long POPUP_MILLIS = 1600L;
    private static final long POPUP_APPEAR_MILLIS = 140L;
    private static final long POPUP_FADE_MILLIS = 350L;
    private static final int BAR_WIDTH = 81;

    private ArrowHud() {
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.options.hudHidden || player.isSpectator()) {
            return;
        }
        AndryushkaArrowEntity arrow = ClientArrowState.activeArrow();
        if (arrow == null && !ClientArrowState.hasArrowItem()) {
            return;
        }

        TextRenderer font = client.textRenderer;
        int left = context.getScaledWindowWidth() / 2 - 91;
        int y = lineY(client, player, context.getScaledWindowHeight());
        long now = ClientArrowState.gameMillis();

        int iconColor = Rainbow.hsvToRgb((now % 3000L) / 3000.0F, 0.8F, 1.0F);
        context.drawTextWithShadow(font, Text.literal(ICON), left, y, iconColor);
        int x = left + font.getWidth(ICON) + 3;
        int level = ClientArrowState.level();
        if (level > 1) {
            String roman = ArrowLevel.roman(level);
            context.drawTextWithShadow(font, Text.literal(roman), x, y, 0xFFC830);
            x += font.getWidth(roman) + 4;
        }
        context.drawTextWithShadow(font, line(arrow, now), x, y, 0xFFFFFF);
        drawPopup(context, font, now);

        if (arrow != null && (arrow.getStatus() == AndryushkaArrowEntity.STATUS_HUNTING
                || arrow.getStatus() == AndryushkaArrowEntity.STATUS_WAITING)) {
            drawTimeBar(context, left, y - 5, arrow, now);
        }
    }

    /**
     * Надпись серии или нового уровня над прицелом: выскакивает крупной, чуть сжимается
     * и гаснет. Серии переливаются, новый уровень — золотой.
     */
    private static void drawPopup(DrawContext context, TextRenderer font, long now) {
        ClientArrowState.Popup popup = ClientArrowState.popup();
        if (popup == null) {
            return;
        }
        long age = now - popup.startedAt();
        if (age < 0L || age >= POPUP_MILLIS) {
            return;
        }
        float appear = Math.min(1.0F, age / (float) POPUP_APPEAR_MILLIS);
        float scale = 2.6F - 0.8F * (1.0F - (1.0F - appear) * (1.0F - appear));
        float alpha = Math.min(1.0F, (POPUP_MILLIS - age) / (float) POPUP_FADE_MILLIS);
        int alphaBits = Math.round(alpha * 255.0F);
        if (alphaBits < 8) {
            // Почти прозрачный текст рисуется непрозрачным — просто не рисуем.
            return;
        }
        int rgb = popup.levelUp() ? 0xFFC830 : Rainbow.hsvToRgb(now / 500.0F, 0.6F, 1.0F);
        int centerX = context.getScaledWindowWidth() / 2;
        int centerY = context.getScaledWindowHeight() / 2 - 46;
        context.getMatrices().push();
        context.getMatrices().translate(centerX, centerY, 0.0F);
        context.getMatrices().scale(scale, scale, 1.0F);
        context.drawCenteredTextWithShadow(font, popup.title(), 0, -4, (alphaBits << 24) | rgb);
        context.getMatrices().pop();
        if (popup.subtitle() != null) {
            context.drawCenteredTextWithShadow(font, popup.subtitle(), centerX, centerY + 14,
                    (alphaBits << 24) | 0xFFE9A0);
        }
    }

    /** Радужная полоска оставшегося времени шириной с полоску здоровья; в последние 5 секунд мигает. */
    private static void drawTimeBar(DrawContext context, int x, int y, AndryushkaArrowEntity arrow, long now) {
        int total = arrow.getMaxLife();
        int remaining = Math.max(0, total - arrow.getSyncedLife());
        int filled = Math.round(BAR_WIDTH * remaining / (float) total);
        boolean warning = remaining <= AndryushkaArrowEntity.WARNING_LEAD_TICKS;
        boolean blinkOn = (now / 250L) % 2L == 0L;
        context.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + 3, 0x90000000);
        for (int i = 0; i < filled; i++) {
            int rgb = warning
                    ? (blinkOn ? 0xFF3030 : 0x801010)
                    : Rainbow.hsvToRgb(i / (float) BAR_WIDTH * 0.8F - now / 4000.0F, 0.7F, 1.0F);
            context.fill(x + i, y, x + i + 1, y + 2, 0xFF000000 | rgb);
        }
    }

    private static Text line(AndryushkaArrowEntity arrow, long now) {
        ArrowMode mode = ClientArrowState.mode();
        boolean flash = ClientArrowState.millisSinceModeChange() < MODE_FLASH_MILLIS;
        Formatting modeColor = flash ? Formatting.WHITE : switch (mode) {
            case TARGET -> Formatting.GOLD;
            case ALL -> Formatting.LIGHT_PURPLE;
            case GUARD -> Formatting.GREEN;
        };
        MutableText line = Text.translatable(mode.translationKey()).formatted(modeColor);
        line.append(Text.literal(" [").append(Text.keybind(ArrowCommand.TOGGLE_MODE.keyTranslation())).append("]")
                .formatted(Formatting.DARK_GRAY));
        if (arrow != null) {
            line.append(Text.literal("  ")).append(status(arrow, now));
            if (arrow.getKills() > 0) {
                // Свежая победа на миг подсвечивает счётчик.
                Formatting killColor = ClientArrowState.millisSinceKill() < KILL_FLASH_MILLIS ? Formatting.YELLOW : Formatting.RED;
                line.append(Text.literal("  ")).append(
                        Text.translatable("hud.andryushka_arrow.kills", arrow.getKills()).formatted(killColor));
            }
        }
        return line;
    }

    private static Text status(AndryushkaArrowEntity arrow, long now) {
        int remainingTicks = Math.max(0, arrow.getMaxLife() - arrow.getSyncedLife());
        int seconds = MathHelper.ceil(remainingTicks / 20.0F);
        return switch (arrow.getStatus()) {
            case AndryushkaArrowEntity.STATUS_RETURNING ->
                    Text.translatable("hud.andryushka_arrow.returning").formatted(Formatting.AQUA);
            case AndryushkaArrowEntity.STATUS_FINALE ->
                    Text.translatable("hud.andryushka_arrow.finale").formatted(Formatting.YELLOW);
            default -> {
                MutableText text = Text.empty();
                if (arrow.getStatus() == AndryushkaArrowEntity.STATUS_WAITING) {
                    text.append(Text.translatable("hud.andryushka_arrow.waiting").formatted(Formatting.GRAY))
                            .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY));
                }
                Formatting timeColor = seconds > 5
                        ? Formatting.WHITE
                        : (now / 250L) % 2L == 0L ? Formatting.RED : Formatting.DARK_RED;
                text.append(Text.translatable("hud.andryushka_arrow.seconds", seconds).formatted(timeColor));
                if (arrow.getBoost() > 1.04F) {
                    text.append(Text.literal(" ")).append(Text.translatable("hud.andryushka_arrow.boost",
                            String.format(Locale.ROOT, "%.1f", arrow.getBoost())).formatted(Formatting.AQUA));
                }
                yield text;
            }
        };
    }

    /** Та же раскладка, что у ванильного HUD: строка сразу над сердечками и бронёй. */
    private static int lineY(MinecraftClient client, ClientPlayerEntity player, int screenHeight) {
        if (client.interactionManager == null || !client.interactionManager.hasStatusBars()) {
            return screenHeight - 34;
        }
        int healthTop = screenHeight - 39;
        float maxHealth = Math.max(player.getMaxHealth(), MathHelper.ceil(player.getHealth()));
        int absorption = MathHelper.ceil(player.getAbsorptionAmount());
        int rows = MathHelper.ceil((maxHealth + absorption) / 2.0F / 10.0F);
        int rowHeight = Math.max(10 - (rows - 2), 3);
        int y = healthTop - (rows - 1) * rowHeight - 10;
        if (player.getArmor() > 0) {
            y -= 10;
        }
        return y;
    }
}
