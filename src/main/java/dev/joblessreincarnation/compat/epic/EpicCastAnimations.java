package dev.joblessreincarnation.compat.epic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.magic.SpellCatalog;
import java.util.EnumMap;
import java.util.Map;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.AnimationManager.AnimationRegistryEvent;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.api.client.animation.property.ClientAnimationProperties;
import yesman.epicfight.api.client.animation.property.JointMaskEntry;
import yesman.epicfight.gameasset.Armatures;

/** Authored upper-body clips. Spell selection and transactions stay in the existing spell lifecycle. */
public final class EpicCastAnimations {
    public record Clips(SpellCatalog spell,AnimationAccessor<StaticAnimation> chant,
            AnimationAccessor<StaticAnimation> hold,AnimationAccessor<StaticAnimation> release) {}
    private static final Map<SpellCatalog,Clips> SETS=new EnumMap<>(SpellCatalog.class);
    // Keep the approved V01 water entry points for existing development integrations.
    public static AnimationAccessor<StaticAnimation> CHANT,HOLD,RELEASE;
    public static AnimationAccessor<StaticAnimation> READING;
    public static final int ENTRY_TICKS=8;
    public static Clips clips(SpellCatalog spell) { return SETS.get(spell); }
    /** Public transient wire values: water remains 1/2; each remaining spell gets its own entry/hold pair. */
    public static int phase(SpellCatalog spell,boolean holding) { return 1+2*spell.ordinal()+(holding?1:0); }
    public static AnimationAccessor<StaticAnimation> fromPhase(int phase) {
        if(phase<1||phase>2*SpellCatalog.values().length)return null;
        var set=clips(SpellCatalog.values()[(phase-1)/2]);
        return set==null?null:phase%2==0?set.hold():set.chant();
    }
    public static boolean same(AssetAccessor<?> a,AssetAccessor<?> b) {
        return a!=null&&b!=null&&a.registryName()!=null&&a.registryName().equals(b.registryName());
    }
    public static boolean isEntryOrHold(AssetAccessor<?> clip) {
        return SETS.values().stream().anyMatch(set->same(clip,set.chant())||same(clip,set.hold()));
    }
    public static boolean isRelease(AssetAccessor<?> clip) {
        return SETS.values().stream().anyMatch(set -> same(clip, set.release()));
    }
    public static void register(AnimationRegistryEvent event) {
        event.newBuilder(JoblessReincarnation.MOD_ID,builder->{
            SETS.clear();
            for(var spell:SpellCatalog.values()) {
                String prefix=spell==SpellCatalog.WATER_BOLT?"v01/player/water":"v02/player/"+spell.id().getPath();
                var chant=builder.nextAccessor(prefix+"_chant",key->upper(false,key));
                var hold=builder.nextAccessor(prefix+"_hold",key->upper(true,key));
                var release=builder.nextAccessor(prefix+"_release",key->upper(false,key));
                SETS.put(spell,new Clips(spell,chant,hold,release));
            }
            var water=SETS.get(SpellCatalog.WATER_BOLT);CHANT=water.chant();HOLD=water.hold();RELEASE=water.release();
            NpcEpicAnimations.register(builder);
            SchoolAnimations.register(builder);
            RastaBearCombat.animations(builder);
            READING = builder.nextAccessor("reading/player_hold", key -> upper(true, key));
        });
    }
    private static StaticAnimation upper(boolean repeat,AnimationAccessor<StaticAnimation> accessor) {
        var animation=new StaticAnimation(.10F,repeat,accessor,Armatures.BIPED);
        animation.addProperty(ClientAnimationProperties.LAYER_TYPE,Layer.LayerType.COMPOSITE_LAYER);
        animation.addProperty(ClientAnimationProperties.PRIORITY,Layer.Priority.HIGH);
        animation.addProperty(ClientAnimationProperties.JOINT_MASK,
                JointMaskEntry.builder().defaultMask(JointMaskEntry.BIPED_UPPER_JOINTS_WITH_ROOT).create());
        return animation;
    }
    private EpicCastAnimations() {}
}
