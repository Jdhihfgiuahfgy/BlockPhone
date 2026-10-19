package dev.blockphone.fabric.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватному {@code GameRenderer.setPostEffect} — так включаются фильтры камеры. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("setPostEffect")
    void blockphone$setPostEffect(Identifier id);
}
