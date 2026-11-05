package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.camera.CameraMode;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Камера: без руки в кадре и без обводки блока под прицелом (зум — FovMixin). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void blockphone$hideHand(CallbackInfo ci) { // без аргументов цели: у 26.x они другие
        if (CameraMode.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void blockphone$hideOutline(CallbackInfoReturnable<Boolean> cir) {
        if (CameraMode.active()) {
            cir.setReturnValue(false);
        }
    }
}
