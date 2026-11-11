package dev.joblessreincarnation.client.monster;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.encounter.RastaBearEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Draws the rasta bear from {@code assets/jobless_reincarnation/{geo,animations,textures}/entity/rasta_bear.*}: the v002 rework
 * of the OFF7-13 model (brow ridge, jaw bone, fangs, shoulder hump, claws), with its body pivot at the hind heels so it rears
 * up on its hind feet. The entity's controller picks the clip. The head does not follow the look direction, which would
 * override the roar in the warning; the game's own death tip-over is off, since the death clip already lays the bear on its
 * side. The ruff and fringe hems rely on GeckoLib's default cutout render type.
 */
public class RastaBearRenderer extends GeoEntityRenderer<RastaBearEntity> {
    /** Option B of OFF7-13: about 1.8 blocks high and 3 long, so it reads from a distance. */
    static final float SCALE = 1.3F;

    public RastaBearRenderer(EntityRendererProvider.Context context) {
        super(context, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "rasta_bear")));
        withScale(SCALE);
        shadowRadius = 0.9F;
    }

    @Override
    protected float getDeathMaxRotation(RastaBearEntity bear) {
        return 0.0F;
    }
}
