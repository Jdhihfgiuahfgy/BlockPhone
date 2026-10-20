package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.phone.PhoneInHandLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Телефон в руке: поднять главную руку после обычной анимации. */
@Mixin(HumanoidModel.class)
abstract class HumanoidModelMixin {
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart head;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void blockphone$phoneInHand(HumanoidRenderState state, CallbackInfo ci) {
        PhoneInHandLayer.poseArm(state, rightArm, leftArm, head);
    }
}
