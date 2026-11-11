package dev.joblessreincarnation.client.npc;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/**
 * A family member's model: the head follows where they look; while the greeting plays the look is composed with the
 * greeting's own head motion inside the member's window, and after it stops the greeting's bones fade to their new
 * pose (see GreetingLook). Paul's model adds his sword demonstration.
 */
class FamilyGeoModel extends DefaultedEntityGeoModel<FamilyResidentEntity> {
    private final GreetingLook.Profile greeting;

    FamilyGeoModel(FamilyResidentEntity.Member member) {
        super(ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, member.id()), true);
        greeting = GreetingLook.of(member);
    }

    @Override
    public void setCustomAnimations(FamilyResidentEntity resident, long instanceId, AnimationState<FamilyResidentEntity> state) {
        GeoBone head = getAnimationProcessor().getBone("head");
        // The clip's head, before super replaces its pitch and yaw with the look.
        float clipX = head == null ? 0.0F : head.getRotX(), clipY = head == null ? 0.0F : head.getRotY();
        super.setCustomAnimations(resident, instanceId, state);
        if (head == null) return;
        double now = state.getAnimationTick(), ticks = GreetingLook.ticks(resident, instanceId, now);
        adjustLook(resident, instanceId, head, now);
        GreetingLook.compose(head, greeting, clipX, clipY, ticks);
        GreetingLook.settle(resident, getAnimationProcessor(), greeting, Double.isFinite(ticks), now);
    }

    /** Changes the look before the greeting is composed; the others keep it as it is. */
    void adjustLook(FamilyResidentEntity resident, long instanceId, GeoBone head, double now) {}
}
