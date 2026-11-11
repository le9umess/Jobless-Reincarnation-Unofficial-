package dev.joblessreincarnation.client.npc;

import dev.joblessreincarnation.npc.FamilyResidentEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.joml.Vector3f;

/** Renderer settings for the three family members, from the accepted v002 models. */
public final class FamilyRendering {
    /**
     * Player scale, like Roxy: the models are built on the same 32-unit figure. With their hair they reach about
     * 2.02-2.06 blocks, so the name tag starts above that.
     */
    private static final float SCALE = 0.9375F;
    private static final float NAME_TAG_CLEARANCE = 2.2F;
    /**
     * Paul holds the practice wooden sword in his right hand (B01). The model's grip centre (8, 3.05, 8) sits in his fist,
     * 0.95 px from the fist's centre (0, -9.95, 0) toward the blade: at the player's 0.75 scale the handle is shorter
     * than his fist, and a centred grip would bury the crossguard in it. The blade lies 45 degrees forward of the forearm
     * (pitch -135), so it points ahead and down at rest and stands up when he raises the arm in sword_demo; flat and
     * crossguard face the front (yaw 0), so it reads as a sword to the player he is talking to. The sword follows the
     * arm (upright 0). Checked offline against his parts and the ground: reference motion/check-paul-sword-demo.mjs.
     */
    private static final Vector3f PAUL_SWORD_GRIP = new Vector3f(0.0F, -10.62F, -0.67F);

    public static EntityRendererProvider<FamilyResidentEntity> provider(FamilyResidentEntity.Member member) {
        return context -> create(context, member);
    }

    private static EntityRenderer<FamilyResidentEntity> create(EntityRendererProvider.Context context, FamilyResidentEntity.Member member) {
        // Every member's model composes the greeting with the look (FamilyGeoModel); Paul's also keeps his head straight
        // while he demonstrates the sword (PaulGeoModel).
        NpcGeoRenderer<FamilyResidentEntity> renderer = new NpcGeoRenderer<FamilyResidentEntity>(context,
                member == FamilyResidentEntity.Member.PAUL ? new PaulGeoModel() : new FamilyGeoModel(member), SCALE)
                .withNameTagClearance(NAME_TAG_CLEARANCE);
        if (member == FamilyResidentEntity.Member.PAUL)
            renderer.withHeldModel("right_arm", NpcHeldModels.PAUL_PRACTICE_SWORD, PAUL_SWORD_GRIP, 3.05F, 0.75F, 0.0F, 0.0F, -135.0F);
        return renderer;
    }

    private FamilyRendering() {}
}
