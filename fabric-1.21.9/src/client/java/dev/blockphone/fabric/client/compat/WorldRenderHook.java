package dev.blockphone.fabric.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;

/** 1.21.9: в Fabric API этой версии нет событий отрисовки мира — текст песни в мире недоступен (на экране — работает). */
public final class WorldRenderHook {
    public interface Callback {
        void render(PoseStack pose, SubmitNodeCollector queue);
    }

    public static boolean beforeEntities(Callback callback) {
        return false;
    }

    private WorldRenderHook() {
    }
}
