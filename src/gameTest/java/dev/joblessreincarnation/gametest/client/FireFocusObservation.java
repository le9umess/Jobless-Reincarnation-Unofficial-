package dev.joblessreincarnation.gametest.client;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.client.FireFocusParticle;
import dev.joblessreincarnation.gametest.FireProbe;
import dev.joblessreincarnation.gametest.MultiplayerSpellProbe;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.particle.Particle;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/** Observes the real native particle provider and SoundEngine dispatch; never included in the release jar. */
@EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT)
public final class FireFocusObservation {
    private static final List<Particle> particles=new ArrayList<>();
    private static int created,readySounds;
    private static float largest;
    public static boolean enabled(){return FireProbe.enabled()||MultiplayerSpellProbe.enabled()||MultiplayerSpellProbe.observer();}
    public static int created(){return created;}
    public static int readySounds(){return readySounds;}
    public static float largestProgress(){return largest;}
    public static int alive(){particles.removeIf(p->!p.isAlive());return particles.size();}
    public static void reset(){particles.clear();created=readySounds=0;largest=0;}
    @SubscribeEvent public static void sound(PlaySoundEvent e){
        if(enabled()&&e.getSound()!=null&&e.getSound().getLocation().equals(SoundEvents.FLINTANDSTEEL_USE.getLocation()))readySounds++;
    }
    @EventBusSubscriber(modid=JoblessReincarnation.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent(priority=EventPriority.LOWEST) public static void providers(RegisterParticleProvidersEvent e){
            if(!enabled())return;
            var delegate=new FireFocusParticle.Provider();
            e.registerSpecial(ModContent.FIRE_FOCUS.get(),(options,level,x,y,z,xSpeed,ySpeed,zSpeed)->{
                    Particle p=delegate.createParticle(options,level,x,y,z,xSpeed,ySpeed,zSpeed);
                    if(p!=null){particles.removeIf(old->!old.isAlive());particles.add(p);created++;largest=Math.max(largest,options.progress());}
                    return p;
            });
        }
    }
    private FireFocusObservation(){}
}
