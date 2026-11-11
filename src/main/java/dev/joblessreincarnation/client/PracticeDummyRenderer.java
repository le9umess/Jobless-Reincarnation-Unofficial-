package dev.joblessreincarnation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.training.PracticeDummyEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Draws the practice dummy from {@code assets/jobless_reincarnation/{geo,animations,textures}/entity/practice_dummy.*}
 * (the OFF3-02 contract: idle and hit_candidate, played by the entity's {@code practice} controller). It is a fixed prop,
 * so nothing turns to follow anyone; the shadow matches its base.
 */
public class PracticeDummyRenderer extends GeoEntityRenderer<PracticeDummyEntity> {
    public PracticeDummyRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "practice_dummy")));
        shadowRadius = 0.4F;
    }

    @Override
    protected void applyRotations(PracticeDummyEntity entity, PoseStack poseStack, float ageInTicks,
            float bodyYaw, float partialTick, float nativeScale) {
        // GeckoLib supplies a body yaw only for LivingEntity. This nonliving prop must use its
        // saved entity yaw, or every placed dummy renders south regardless of who placed it.
        super.applyRotations(entity, poseStack, ageInTicks,
                Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot()), partialTick, nativeScale);
    }
}