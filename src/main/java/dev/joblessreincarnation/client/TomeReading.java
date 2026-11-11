package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.joblessreincarnation.compat.epic.EpicCastAnimations;
import dev.joblessreincarnation.magic.SpellCasting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/** Use Minecraft's current held stack for the reader and for any tracking player. */
public final class TomeReading implements IClientItemExtensions {
    static boolean isReading(LivingEntity reader, ItemStack stack) {
        return reader != null && reader.isAlive() && reader.isUsingItem()
                && reader.getUsedItemHand() == InteractionHand.MAIN_HAND
                && reader.getUseItem() == stack && reader.getMainHandItem() == stack
                && SpellCasting.isStudyTome(stack);
    }

    static float opening(LivingEntity reader, float partialTick) {
        float t = Mth.clamp((reader.getTicksUsingItem() + partialTick) / 6.0F, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    @Override
    public boolean applyForgeHandTransform(PoseStack poses, LocalPlayer reader, HumanoidArm arm,
            ItemStack stack, float partialTick, float equip, float swing) {
        if (arm != reader.getMainArm() || !isReading(reader, stack)) return false;
        float lift = opening(reader, partialTick);
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        poses.translate(side * (0.56F - 0.44F * lift), -0.52F - 0.6F * equip + 0.21F * lift,
                -0.72F - 0.11F * lift);
        return true;
    }

    @Override
    public HumanoidModel.ArmPose getArmPose(LivingEntity reader, InteractionHand hand, ItemStack stack) {
        return hand == InteractionHand.MAIN_HAND && isReading(reader, stack) ? ArmPoseParameters.READING.getValue() : null;
    }

    /** Used when Epic Fight selects the vanilla player renderer. */
    public static final class ArmPoseParameters {
        public static final EnumProxy<HumanoidModel.ArmPose> READING = new EnumProxy<>(
                HumanoidModel.ArmPose.class, true, (IArmPoseTransformer) (model, entity, arm) -> {
                    model.rightArm.xRot = model.leftArm.xRot = -1.05F + model.head.xRot * 0.25F;
                    model.rightArm.yRot = -0.36F;
                    model.leftArm.yRot = 0.36F;
                });
        private ArmPoseParameters() {}
    }

    public static void syncPose(AbstractClientPlayer player) {
        var patch = EpicFightCapabilities.getPlayerPatch(player);
        if (patch == null) return;
        var animator = patch.getClientAnimator();
        var layer = animator.getCompositeLayer(Layer.Priority.HIGH);
        var current = layer.animationPlayer.getRealAnimation();
        boolean ours = EpicCastAnimations.same(current, EpicCastAnimations.READING);
        boolean active = isReading(player, player.getMainHandItem()) && !patch.getEntityState().inaction();
        if (active && !ours && layer.isOff()) animator.playAnimation(EpicCastAnimations.READING, 0.0F);
        if (!active && ours && !layer.isOff()) animator.stopPlaying(current);
    }
}
