package dev.blockphone.fabric.client.apps.weather;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

/** Погода в текущем месте мира: биом, осадки, «температура», время суток и фаза луны. */
public final class WeatherApp extends PhoneApp {

    private enum Condition {
        CLEAR_DAY("sun", 0xFF4A90E2, 0xFF87CEFA),
        CLEAR_NIGHT("moon", 0xFF0B1026, 0xFF2B3A67),
        RAIN("rain", 0xFF4B5563, 0xFF9CA3AF),
        THUNDER("thunder", 0xFF1F2937, 0xFF4B5563),
        SNOW("snow", 0xFF7DA2C8, 0xFFDDE7F0);

        final String icon;
        final int top;
        final int bottom;

        Condition(String icon, int top, int bottom) {
            this.icon = icon;
            this.top = top;
            this.bottom = bottom;
        }

        Identifier texture() {
            return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/weather/" + icon + ".png");
        }
    }

    public WeatherApp() {
        super("weather");
    }

    @Override
    public boolean hasHeader() {
        return false;
    }

    @Override
    public boolean lightStatusBar() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            PhoneDraw.centeredText(g, PhoneLang.tr("weather.no_world"), contentWidth() / 2f, contentHeight() / 2f - 4,
                    0xFF8E8E93, 0.75f, false);
            return;
        }
        BlockPos pos = player.blockPosition();
        Holder<Biome> biome = level.getBiome(pos);
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        boolean night = dayTime >= 13000 && dayTime < 23000;

        Biome.Precipitation precipitation = biome.value().getPrecipitationAt(pos, level.getSeaLevel());
        Condition condition;
        if (level.isRaining() && precipitation == Biome.Precipitation.SNOW) {
            condition = Condition.SNOW;
        } else if (level.isThundering() && precipitation == Biome.Precipitation.RAIN) {
            condition = Condition.THUNDER;
        } else if (level.isRaining() && precipitation == Biome.Precipitation.RAIN) {
            condition = Condition.RAIN;
        } else {
            condition = night ? Condition.CLEAR_NIGHT : Condition.CLEAR_DAY;
        }

        int w = contentWidth();
        int h = contentHeight();
        g.fillGradient(0, -20, w, h, condition.top, condition.bottom);

        float cx = w / 2f;
        String biomeName = biome.unwrapKey()
                .map(key -> Component.translatable(key.identifier().toLanguageKey("biome")).getString())
                .orElse("?");
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(biomeName, w - 16, 1.25f), cx, 12, 0xFFFFFFFF, 1.25f, true);

        PhoneDraw.texture(g, condition.texture(), (int) cx - 24, 30, 48, 48, 64, 64);

        int temperature = temperature(biome.value(), pos, night, level.isRaining());
        PhoneDraw.centeredText(g, temperature + "°", cx + 4, 84, 0xFFFFFFFF, 3f, true);
        PhoneDraw.centeredText(g, PhoneLang.tr("weather.condition." + condition.name().toLowerCase()), cx, 114, 0xFFFFFFFF, 1f, true);

        // Карточки с деталями.
        int cardTop = 136;
        int moon = (int) Math.floorMod(level.getDayTime() / 24000L, 8L);
        String[][] cards = {
                {PhoneLang.tr("weather.time"), PhoneClock.gameTime(level)},
                {PhoneLang.tr("weather.day"), String.valueOf(level.getDayTime() / 24000L + 1)},
                {PhoneLang.tr("weather.moon"), PhoneLang.tr("weather.moon." + moon)},
                {PhoneLang.tr("weather.altitude"), String.valueOf(pos.getY())},
        };
        int cardW = (w - 24) / 2;
        int cardH = 34;
        for (int i = 0; i < cards.length; i++) {
            int x = 8 + (i % 2) * (cardW + 8);
            int y = cardTop + (i / 2) * (cardH + 8);
            PhoneDraw.roundedRect(g, x, y, x + cardW, y + cardH, 8, 0x33FFFFFF);
            PhoneDraw.text(g, cards[i][0], x + 6, y + 6, 0xCCFFFFFF, 0.6f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(cards[i][1], cardW - 12, 1f), x + 6, y + 17, 0xFFFFFFFF, 1f, false);
        }
    }

    /** «Температура» в °C по базовой температуре биома, с поправкой на высоту, ночь и дождь. */
    private static int temperature(Biome biome, BlockPos pos, boolean night, boolean raining) {
        float t = biome.getBaseTemperature() * 25f - 5f;
        if (pos.getY() > 80) {
            t -= (pos.getY() - 80) * 0.1f;
        }
        if (night) {
            t -= 5f;
        }
        if (raining) {
            t -= 3f;
        }
        return Math.round(t);
    }
}
