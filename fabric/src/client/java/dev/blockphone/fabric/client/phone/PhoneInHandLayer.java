package dev.blockphone.fabric.client.phone;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.blockphone.common.BlockPhone;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Телефон в руке у игрока, который пользуется телефоном ({@link PhoneHolders}): рука поднята, вместо предмета в главной
 * руке — маленький телефон. Предмет не трогаем: только не рисуем его, пока телефон в руке.
 */
public final class PhoneInHandLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final RenderStateDataKey<Boolean> HOLDING = RenderStateDataKey.create(() -> "blockphone:phone_in_hand");
    private static final RenderStateDataKey<Boolean> SIDEWAYS = RenderStateDataKey.create(() -> "blockphone:phone_sideways");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/entity/phone_in_hand.png");
    private static ModelPart phone;
    /** Где телефон в кулаке (полупиксели от плеча вдоль руки) и насколько он уходит за кулак (по Z). */
    private static final float PHONE_GRIP_Y = 18;
    private static final float PHONE_GRIP_Z = 1;

    private PhoneInHandLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @SuppressWarnings("unchecked")
    public static void init() {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?>) {
                helper.register(new PhoneInHandLayer((RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer));
            }
        });
    }

    /** Из {@code AvatarRendererMixin}: отметить, что игрок с телефоном, и спрятать предмет главной руки. */
    public static void extract(Avatar avatar, AvatarRenderState state) {
        boolean holding = !state.isSpectator && PhoneHolders.holding(avatar.getUUID());
        boolean sideways = holding && PhoneHolders.sideways(avatar.getUUID());
        ((FabricRenderState) state).setData(HOLDING, holding);
        ((FabricRenderState) state).setData(SIDEWAYS, sideways);
        if (!holding) {
            return;
        }
        if (sideways) {
            // Двумя руками — обе руки свободны от предметов.
            state.rightHandItemState.clear();
            state.leftHandItemState.clear();
            state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
            state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        } else if (state.mainArm == HumanoidArm.RIGHT) {
            state.rightHandItemState.clear();
            state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        } else {
            state.leftHandItemState.clear();
            state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        }
    }

    /** Из {@code HumanoidModelMixin}: после обычной позы поднять главную руку с телефоном перед собой. */
    public static void poseArm(HumanoidRenderState state, ModelPart rightArm, ModelPart leftArm, ModelPart head) {
        if (!holding(state) || state.swimAmount > 0 || state.isFallFlying) {
            return;
        }
        if (sideways(state)) {
            // Телефон набок, как джойстик: обе руки вперёд и чуть внутрь, кисти сходятся перед грудью.
            float x = -1.0f + head.xRot * 0.35f + (state.isCrouching ? 0.4f : 0f);
            rightArm.xRot = x;
            leftArm.xRot = x;
            rightArm.yRot = -0.42f;
            leftArm.yRot = 0.42f;
            rightArm.zRot = 0f;
            leftArm.zRot = 0f;
            return;
        }
        boolean right = state.mainArm == HumanoidArm.RIGHT;
        ModelPart arm = right ? rightArm : leftArm;
        arm.xRot = -1.05f + head.xRot * 0.35f + (state.isCrouching ? 0.4f : 0f);
        arm.yRot = right ? -0.45f : 0.45f;
        arm.zRot = 0f;
    }

    private static boolean holding(Object state) {
        return Boolean.TRUE.equals(((FabricRenderState) state).getData(HOLDING));
    }

    private static boolean sideways(Object state) {
        return Boolean.TRUE.equals(((FabricRenderState) state).getData(SIDEWAYS));
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        if (!holding(state)) {
            return;
        }
        if (phone == null) {
            MeshDefinition mesh = new MeshDefinition();
            // Размеры в полупикселях (поза уменьшена вдвое): 3×6×0.5 пикселя. Коробка повёрнута на 90° вокруг X:
            // телефон стоит в кулаке поперёк руки (длинная сторона — вверх, когда рука поднята), экран (+Z развёртки)
            // смотрит к плечу, то есть на лицо игрока. Раньше телефон лежал плашмя вдоль руки.
            mesh.getRoot().addOrReplaceChild("phone", CubeListBuilder.create().texOffs(0, 0).addBox(-3, -12, 0, 6, 12, 1),
                    PartPose.offsetAndRotation(0, PHONE_GRIP_Y, PHONE_GRIP_Z, (float) (Math.PI / 2), 0, 0));
            phone = LayerDefinition.create(mesh, 32, 32).bakeRoot().getChild("phone");
        }
        pose.pushPose();
        getParentModel().translateToHand(state, state.mainArm, pose);
        pose.scale(0.5f, 0.5f, 0.5f);
        if (sideways(state)) {
            // Набок: телефон между кистями, длинная сторона поперёк (повернуть вокруг оси руки), сдвинут к другой руке.
            boolean right = state.mainArm == HumanoidArm.RIGHT;
            phone.x = right ? 2.5f : -2.5f;
            phone.zRot = (float) (Math.PI / 2);
            phone.y = PHONE_GRIP_Y - 3;
        } else {
            phone.x = state.mainArm == HumanoidArm.RIGHT ? -2 : 2;
            phone.zRot = 0;
            phone.y = PHONE_GRIP_Y;
        }
        collector.submitModelPart(phone, pose, RenderTypes.entityCutout(TEXTURE), light, OverlayTexture.NO_OVERLAY, null);
        pose.popPose();
    }
}
