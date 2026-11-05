package dev.blockphone.fabric.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;

/** Рисование в мире вместе с сущностями (текст песни в мире) — версия для 26.1+: события LevelRenderEvents. */
public final class WorldRenderHook {
    public interface Callback {
        void render(PoseStack pose, SubmitNodeCollector queue);
    }

    public static boolean beforeEntities(Callback callback) {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> callback.render(context.poseStack(), context.submitNodeCollector()));
        return true;
    }

    private WorldRenderHook() {
    }
}
