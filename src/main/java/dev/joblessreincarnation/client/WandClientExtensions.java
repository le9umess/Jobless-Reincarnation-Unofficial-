package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

/** Presentation only: casting state continues to come from ordinary item use. */
public final class WandClientExtensions implements IClientItemExtensions {
    @Override
    public boolean applyForgeHandTransform(PoseStack poseStack, LocalPlayer player, HumanoidArm arm,
            ItemStack itemInHand, float partialTick, float equipProcess, float swingProcess) {
        if (arm != player.getMainArm() || !isCastingWand(player, itemInHand)) return false;

        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float raise = raiseProgress(player, partialTick);
        float breathing = Mth.sin((player.tickCount + partialTick) * 0.08F) * 0.003F * raise;

        // Start at vanilla's equipped-hand position. The model's display transform
        // places its grip at the origin; only the wrist moves, never the wand's scale.
        poseStack.translate(side * (0.56F - 0.18F * raise),
                -0.52F - 0.6F * equipProcess + 0.13F * raise + breathing,
                -0.72F - 0.22F * raise);
        poseStack.mulPose(Axis.XP.rotationDegrees(-60.0F * raise));
        poseStack.mulPose(Axis.YP.rotationDegrees(side * 8.0F * raise));
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * 24.0F * raise));
        return true;
    }

    @Override
    @Nullable
    public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
        return hand == InteractionHand.MAIN_HAND && isCastingWand(entity, stack)
                ? ArmPoseParameters.WAND_CAST.getValue() : null;
    }

    private static boolean isCastingWand(LivingEntity entity, ItemStack stack) {
        return entity.isAlive() && entity.isUsingItem() && entity.getUseItemRemainingTicks() > 0
                && entity.getUsedItemHand() == InteractionHand.MAIN_HAND
                && stack.is(ModContent.TRAINING_STAFF.get())
                && entity.getUseItem().is(ModContent.TRAINING_STAFF.get())
                && entity.getMainHandItem().is(ModContent.TRAINING_STAFF.get());
    }

    private static float raiseProgress(LivingEntity entity, float partialTick) {
        float progress = Mth.clamp((entity.getTicksUsingItem() - 1.0F + partialTick) / 6.0F, 0.0F, 1.0F);
        return progress * progress * (3.0F - 2.0F * progress);
    }

    /**
     * Loaded only when the client ArmPose enum initializes. Keeping its parameters
     * in a separate class avoids resolving registries or the mod entry point early.
     */
    public static final class ArmPoseParameters {
        public static final EnumProxy<HumanoidModel.ArmPose> WAND_CAST = new EnumProxy<>(
                HumanoidModel.ArmPose.class, false, (IArmPoseTransformer) ArmPoseParameters::poseCastingArm);

        private static void poseCastingArm(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
            ModelPart castingArm = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
            int side = arm == HumanoidArm.RIGHT ? 1 : -1;
            float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
            float raise = raiseProgress(entity, partialTick);
            float pitch = model.head.xRot - Mth.HALF_PI + 0.08F;
            // HumanoidModel adds its crouch offset after the custom pose callback.
            if (model.crouching) pitch -= 0.4F;
            castingArm.xRot = Mth.lerp(raise, castingArm.xRot, pitch);
            castingArm.yRot = Mth.lerp(raise, castingArm.yRot, model.head.yRot - side * 0.08F);
            castingArm.zRot = Mth.lerp(raise, castingArm.zRot, -side * 0.04F);
            // The other arm retains vanilla's walking, carrying and idle motion.
        }

        private ArmPoseParameters() {}
    }
}
