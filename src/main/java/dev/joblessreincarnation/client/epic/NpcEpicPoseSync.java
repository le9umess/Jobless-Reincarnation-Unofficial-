package dev.joblessreincarnation.client.epic;

import dev.joblessreincarnation.compat.epic.NpcEpicAnimations;
import dev.joblessreincarnation.compat.epic.NpcGestureVisual;
import dev.joblessreincarnation.registry.ModAttachments;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.api.animation.types.LayerOffAnimation;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Continuous time reconciliation, independent of whether the NPC is currently in the camera frustum. */
public final class NpcEpicPoseSync {
    private record Applied(long sequence,NpcGestureVisual.Kind kind) {}
    private static final Map<Entity,Applied> APPLIED=new WeakHashMap<>();
    private static boolean same(AssetAccessor<?> a,AssetAccessor<?> b) { return a!=null&&b!=null&&a.registryName()!=null&&a.registryName().equals(b.registryName()); }
    private static boolean owned(AssetAccessor<?> a) { return a!=null&&a.registryName()!=null&&a.registryName().getNamespace().equals("jobless_reincarnation")&&a.registryName().getPath().startsWith("v02/"); }
    public static void tick(ClientTickEvent.Post event) {
        var c=Minecraft.getInstance();if(c.level==null){APPLIED.clear();return;}
        for(var e:c.level.entitiesForRendering()) {
            if(NpcEpicAnimations.role(e)==null)continue;
            var patch=EpicFightCapabilities.getEntityPatch(e,LivingEntityPatch.class);if(patch==null)continue;
            var clips=NpcEpicAnimations.of(e);if(clips==null)continue;
            var state=e.getData(ModAttachments.NPC_GESTURE_VISUAL);
            var desired=state.live()?clips.gestures().get(state.kind()):null;
            var layer=patch.getClientAnimator().getCompositeLayer(Layer.Priority.HIGH);
            var current=layer.animationPlayer.getRealAnimation();
            var applied=APPLIED.get(e);
            if(desired==null) {
                if(owned(current)) {
                    layer.off(patch);
                    if(layer.animationPlayer.getAnimation().get() instanceof LayerOffAnimation off)
                        off.setTotalTime(applied!=null&&applied.kind()==NpcGestureVisual.Kind.SWORD_DEMO?.10F:.20F);
                }
                APPLIED.remove(e);continue;
            }
            boolean fresh=applied==null||applied.sequence()!=state.sequence()||applied.kind()!=state.kind();
            double age=state.age(0);
            if(fresh||!same(current,desired)) {
                // Do not overwrite unrelated HIGH-priority animation supplied by another integration.
                if(!layer.isOff()&&!owned(current))continue;
                if(age<NpcGestureVisual.ENTRY_TICKS)patch.getClientAnimator().playAnimation(desired,0.0F);
                else patch.getClientAnimator().playAnimationInstantly(desired);
                APPLIED.put(e,new Applied(state.sequence(),state.kind()));
            }
            if(!same(layer.animationPlayer.getRealAnimation(),desired))continue;
            float now,prev;
            if(layer.animationPlayer.getAnimation().get().isLinkAnimation()) {
                now=(float)Math.min(.2,age/20.0);prev=(float)Math.min(.2,Math.max(0,age-1)/20.0);
            } else {
                now=(float)Math.max(0,(age-NpcGestureVisual.ENTRY_TICKS)/20.0);
                prev=(float)Math.max(0,(age-NpcGestureVisual.ENTRY_TICKS-1)/20.0);
            }
            layer.animationPlayer.setElapsedTime(prev,now);
        }
    }
    private NpcEpicPoseSync() {}
}
