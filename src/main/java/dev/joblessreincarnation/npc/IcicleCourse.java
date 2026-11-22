package dev.joblessreincarnation.npc;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
/** Private, additive save data. Unknown old saves start here without altering any existing lesson. */
public record IcicleCourse(int stage,int frozen) {
    public static final IcicleCourse NEW=new IcicleCourse(0,0);
    public static final Codec<IcicleCourse> CODEC=RecordCodecBuilder.create(i->i.group(
        Codec.intRange(0,4).optionalFieldOf("stage",0).forGetter(IcicleCourse::stage),
        Codec.intRange(0,6).optionalFieldOf("frozen",0).forGetter(IcicleCourse::frozen)).apply(i,IcicleCourse::new));
    public IcicleCourse {stage=Math.clamp(stage,0,4);frozen=Math.clamp(frozen,0,6);}
    public boolean complete(){return stage==4;}
}
