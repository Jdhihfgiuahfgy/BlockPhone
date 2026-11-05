package dev.blockphone.fabric.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Рисование в мире перед сущностями (текст песни в мире). Переходник: в Fabric API для 1.21.9 событий отрисовки
 * мира нет — там свой вариант этого класса (fabric-1.21.9/src), который ничего не подключает.
 */
public final class WorldRenderHook {
    public interface Callback {
        void render(PoseStack pose, SubmitNodeCollector queue);
    }

    /** Подключить; false — в этой версии Minecraft нельзя. */
    public static boolean beforeEntities(Callback callback) {
        WorldRenderEvents.BEFORE_ENTITIES.register(context -> callback.render(context.matrices(), context.commandQueue()));
        return true;
    }

    private WorldRenderHook() {
    }
}
