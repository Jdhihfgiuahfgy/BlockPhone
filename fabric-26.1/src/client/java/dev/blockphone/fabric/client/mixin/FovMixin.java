package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.camera.CameraMode;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Камера: зум через угол обзора — версия для 26.x (угол считает Camera.getFov). */
@Mixin(Camera.class)
public abstract class FovMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void blockphone$zoom(CallbackInfoReturnable<Float> cir) {
        float divisor = CameraMode.fovDivisor();
        if (divisor != 1f) {
            cir.setReturnValue(cir.getReturnValueF() / divisor);
        }
    }
}
