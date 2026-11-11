package dev.joblessreincarnation.client.npc;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoAnimatable;

/** Roxy's renderer settings in one place, shared by the released NPC and the development display entity. */
public final class RoxyRendering {
    /** Her whole figure: 90% of what 0.32.0 drew (0.9375 twice), the user's choice from in-game shots beside the other NPCs. */
    public static final float SCALE = 0.79F;
    /**
     * Where her right hand holds the staff, in pixels from the shoulder pivot: the hand is 9.55 px down; the staff sits
     * at the hand's outer edge and a little forward, so it clears the robe instead of running through the arm.
     */
    private static final Vector3f STAFF_GRIP = new Vector3f(1.7F, -9.55F, -0.8F);
    /**
     * Share of the arm's forward and back swing the hand takes out of the staff (see {@link NpcHeldModelLayer}): all of
     * it, so the staff stands upright while she walks and stays in front of her face, clear of the hat brim, in the lecture.
     */
    private static final float STAFF_UPRIGHT = 1.0F;

    public static <T extends Entity & GeoAnimatable> NpcGeoRenderer<T> create(EntityRendererProvider.Context context) {
        // The hat tip reaches about 2.01 blocks at this scale, so the name tag starts above it.
        return new NpcGeoRenderer<T>(context, "roxy_tutor", SCALE).withNameTagClearance(2.15F)
                // Grip 14 px up the staff, so with the arm hanging the butt rests on the ground and the crook reaches about
                // head height; turned 180 degrees so the crook curls outward, away from her head and hat. Her hand keeps the
                // staff upright, so when the arm comes forward in the lecture the crook does not fall back onto her hat.
                .withHeldModel("right_arm", NpcHeldModels.ROXY_STAFF, STAFF_GRIP, 14.0F, 0.96F, 180.0F, STAFF_UPRIGHT);
    }

    private RoxyRendering() {}
}
