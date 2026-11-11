package dev.joblessreincarnation.client.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.SylphietteEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/** Renderer settings for Sylphiette, from the buena_village/sylphiette model package (v003). */
public final class SylphietteRendering {
    /**
     * Player scale, like the others: the model is a child on the same unit grid, about 1.3 blocks tall with the curled
     * strand of hair, so the name tag starts above that.
     */
    private static final float SCALE = 0.9375F;
    private static final float NAME_TAG_CLEARANCE = 1.45F;
    private static final float SHADOW_RADIUS = 0.3F;
    /**
     * How far below her gaze the head sits. GeckoLib's head turn replaces the head rotation of the idle clip, so the
     * slightly lowered head of a shy child (novel 010 P359-360: hunched, looking up at Rudeus) is kept here instead.
     */
    private static final float SHY_PITCH = 6.0F * Mth.DEG_TO_RAD;

    public static EntityRenderer<SylphietteEntity> create(EntityRendererProvider.Context context) {
        return new NpcGeoRenderer<>(context, new Model(), SCALE).withNameTagClearance(NAME_TAG_CLEARANCE).withShadowRadius(SHADOW_RADIUS);
    }

    /**
     * The default model with the head following the gaze, composed with the greeting (GreetingLook), then lowered a little;
     * after the greeting stops its bones fade to their new pose.
     */
    private static final class Model extends DefaultedEntityGeoModel<SylphietteEntity> {
        Model() { super(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, SylphietteEntity.ID), true); }

        @Override
        public void setCustomAnimations(SylphietteEntity animatable, long instanceId, AnimationState<SylphietteEntity> state) {
            GeoBone head = getAnimationProcessor().getBone("head");
            // The clip's head, before super replaces its pitch and yaw with the gaze.
            float clipX = head == null ? 0.0F : head.getRotX(), clipY = head == null ? 0.0F : head.getRotY();
            super.setCustomAnimations(animatable, instanceId, state);
            if (head == null) return;
            double now = state.getAnimationTick(), ticks = GreetingLook.ticks(animatable, instanceId, now);
            GreetingLook.compose(head, GreetingLook.SYLPHIETTE, clipX, clipY, ticks);
            // The renderer negates the entity pitch (down is positive in Minecraft) before the head gets it, so a
            // lower head is a smaller rotX here. super has just set rotX from the gaze, so this never accumulates; the
            // greeting adds only its clip's change from the clip's first frame, which already holds idle's lowered head,
            // so the head is lowered once.
            head.setRotX(head.getRotX() - SHY_PITCH);
            // Last, so what it records and fades is what is on screen, lowered head included on both sides.
            GreetingLook.settle(animatable, getAnimationProcessor(), GreetingLook.SYLPHIETTE, Double.isFinite(ticks), now);
        }
    }

    private SylphietteRendering() {}
}
