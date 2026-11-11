package dev.joblessreincarnation.compat.epic;

import java.util.Map;
import java.util.WeakHashMap;
import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.magic.SpellCatalog;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;

/** Transient server-selected visual phases; never pays mana, casts, or awards progress. */
public final class EpicCastController {
    private record Active(EpicCastAnimations.Clips clips,AnimationAccessor<StaticAnimation> phase) {}
    private static final Map<ServerPlayer,Active> ACTIVE=new WeakHashMap<>();
    public static void onTick(PlayerTickEvent.Post event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        var spell=SpellCatalog.active(player);
        var clips=EpicCastAnimations.clips(spell);
        if(!SpellCasting.validActive(player)||clips==null){finish(player,false);return;}
        var patch=EpicFightCapabilities.getServerPlayerPatch(player);
        if(patch==null){finish(player,false);return;}
        boolean holding=player.getTicksUsingItem()>=EpicCastAnimations.ENTRY_TICKS;
        var desired=holding?clips.hold():clips.chant();
        var current=ACTIVE.get(player);
        if(current!=null&&EpicCastAnimations.same(current.phase(),desired))return;
        ACTIVE.put(player,new Active(clips,desired));
        player.setData(ModAttachments.EPIC_WATER_PHASE,EpicCastAnimations.phase(spell,holding));
        patch.playAnimationSynchronized(desired,0.0F);
    }
    /** Called only after the authoritative spell transaction returns its actual result. */
    public static void finish(ServerPlayer player,boolean successfulCast) {
        if(player.getData(ModAttachments.EPIC_WATER_PHASE)!=0)player.setData(ModAttachments.EPIC_WATER_PHASE,0);
        Active previous=ACTIVE.remove(player);if(previous==null)return;
        var patch=EpicFightCapabilities.getServerPlayerPatch(player);if(patch==null)return;
        if(successfulCast&&player.isAlive())patch.playAnimationSynchronized(previous.clips().release(),0.0F);
        else patch.stopPlaying(previous.phase()); // Stop only this cast's animation, preserving unrelated actions.
    }
    public static boolean hasActiveVisual(ServerPlayer player){return ACTIVE.containsKey(player);}
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer player)finish(player,false);}
    public static void onServerStopped(ServerStoppedEvent event){ACTIVE.clear();}
    private EpicCastController() {}
}
