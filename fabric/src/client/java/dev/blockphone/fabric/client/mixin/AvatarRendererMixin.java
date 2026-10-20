package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.phone.PhoneInHandLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Телефон в руке: отметка в состоянии отрисовки игрока (действует всегда, не только в камере). */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void blockphone$phoneInHand(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        PhoneInHandLayer.extract(avatar, state);
    }
}
