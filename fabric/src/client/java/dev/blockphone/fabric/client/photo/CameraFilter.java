package dev.blockphone.fabric.client.photo;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Фильтры камеры и галереи. Параметры берутся из постэффекта
 * {@code assets/blockphone/post_effect/filter_<id>.json} (генерируется tools/gen_filters.py),
 * поэтому шейдер в видоискателе и обработка фото на CPU совпадают.
 */
public enum CameraFilter {
    NONE("none"), BW("bw"), SEPIA("sepia"), VINTAGE("vintage"), COOL("cool"),
    WARM("warm"), NOIR("noir"), VIVID("vivid"), FADED("faded"), NIGHT("night");

    /**
     * Параметры фильтра: строки цветовой матрицы {kR, kG, kB, смещение}, насыщенность, контраст, виньетка.
     */
    public record Params(float[] rowR, float[] rowG, float[] rowB, float saturation, float contrast, float vignette) {
        static final Params IDENTITY = new Params(new float[]{1, 0, 0, 0}, new float[]{0, 1, 0, 0},
                new float[]{0, 0, 1, 0}, 1, 1, 0);
    }

    public final String id;
    private Params params;

    CameraFilter(String id) {
        this.id = id;
    }

    public String displayName() {
        return PhoneLang.tr("filter." + id);
    }

    /** Постэффект для видоискателя; null — без фильтра. */
    public @Nullable Identifier postEffect() {
        return this == NONE ? null : Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "filter_" + id);
    }

    public Params params() {
        if (params == null) {
            params = this == NONE ? Params.IDENTITY : load();
        }
        return params;
    }

    public static CameraFilter byId(String id) {
        for (CameraFilter filter : values()) {
            if (filter.id.equals(id)) {
                return filter;
            }
        }
        return NONE;
    }

    private Params load() {
        String path = "/assets/blockphone/post_effect/filter_" + id + ".json";
        try (InputStream in = CameraFilter.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing " + path);
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject uniforms = root.getAsJsonArray("passes").get(0).getAsJsonObject().getAsJsonObject("uniforms");
            float[] r = null, g = null, b = null, p = null;
            for (JsonElement element : uniforms.getAsJsonArray("FilterConfig")) {
                JsonObject u = element.getAsJsonObject();
                float[] value = new float[4];
                for (int i = 0; i < 4; i++) {
                    value[i] = u.getAsJsonArray("value").get(i).getAsFloat();
                }
                switch (u.get("name").getAsString()) {
                    case "RowR" -> r = value;
                    case "RowG" -> g = value;
                    case "RowB" -> b = value;
                    case "Params" -> p = value;
                    default -> {
                    }
                }
            }
            return new Params(r, g, b, p[0], p[1], p[2]);
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to load filter {}", id, e);
            return Params.IDENTITY;
        }
    }
}
