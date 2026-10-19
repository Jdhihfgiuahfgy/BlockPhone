package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.camera.CameraMode;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Камера: зум через FOV, без руки в кадре и без обводки блока под прицелом. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void blockphone$zoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        float divisor = CameraMode.fovDivisor();
        if (divisor != 1f) {
            cir.setReturnValue(cir.getReturnValueF() / divisor);
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void blockphone$hideHand(float partialTick, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
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
