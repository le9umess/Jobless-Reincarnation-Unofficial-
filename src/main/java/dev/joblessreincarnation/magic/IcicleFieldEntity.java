package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import org.jetbrains.annotations.Nullable;

/** Public geometry/time only; paid field membership and personal lesson progress stay on the server. */
public final class IcicleFieldEntity extends Entity {
    public static final double RADIUS=3;
    public static final int FORM_TICKS=10, STABLE_TICKS=120, FADE_TICKS=20, LIFETIME_TICKS=150;
    private static final EntityDataAccessor<Long> START=SynchedEntityData.defineId(IcicleFieldEntity.class,EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> FADE=SynchedEntityData.defineId(IcicleFieldEntity.class,EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> DEMO=SynchedEntityData.defineId(IcicleFieldEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> SOURCE=SynchedEntityData.defineId(IcicleFieldEntity.class,EntityDataSerializers.INT);
    @Nullable private ServerPlayer owner;
    final Set<BlockPos> frozen=new HashSet<>();
    final Set<UUID> triggered=new HashSet<>();
    boolean terrainApplied;
    private boolean endingCuePlayed;
    public IcicleFieldEntity(EntityType<? extends IcicleFieldEntity> type,Level level){super(type,level);noPhysics=true;setNoGravity(true);}
    public IcicleFieldEntity(ServerLevel level,ServerPlayer owner,Vec3 base){this(ModContent.ICICLE_FIELD.get(),level);this.owner=owner;setPos(base);entityData.set(START,level.getGameTime());entityData.set(SOURCE,owner.getId());}
    public static IcicleFieldEntity demonstration(ServerLevel level,Entity teacher,Vec3 base){var e=new IcicleFieldEntity(ModContent.ICICLE_FIELD.get(),level);e.setPos(base);e.entityData.set(START,level.getGameTime());e.entityData.set(DEMO,true);e.entityData.set(SOURCE,teacher.getId());e.entityData.set(FADE,level.getGameTime()+40);return e;}
    @Override protected void defineSynchedData(SynchedEntityData.Builder b){b.define(START,0L).define(FADE,-1L).define(DEMO,false).define(SOURCE,-1);}
    public @Nullable ServerPlayer owner(){return owner;}
    public long startGameTime(){return entityData.get(START);}
    public int sourceId(){return entityData.get(SOURCE);}
    public boolean demonstration(){return entityData.get(DEMO);}
    public float ageTicks(float partial){return net.minecraft.util.Mth.clamp(level().getGameTime()-startGameTime()+partial,0,LIFETIME_TICKS);}
    public long fadeGameTime(){return entityData.get(FADE)>=0?entityData.get(FADE):startGameTime()+FORM_TICKS+STABLE_TICKS;}
    public boolean activeAt(long now){return !demonstration()&&now>=startGameTime()+FORM_TICKS&&now<fadeGameTime();}
    public void fade(){if(entityData.get(FADE)<0)entityData.set(FADE,level().getGameTime());endingCue();}
    private void endingCue(){if(!endingCuePlayed&&level() instanceof ServerLevel server){endingCuePlayed=true;IcicleVisuals.end(server,position());}}
    @Override public void tick(){super.tick();if(level().isClientSide){IcicleVisuals.clientTick(this);return;}
        if(level().getGameTime()>=fadeGameTime())endingCue();
        if(level().getGameTime()<startGameTime()||level().getGameTime()>=fadeGameTime()+FADE_TICKS)discard();
        else if(!demonstration()&&(!IcicleFields.registered(this)||owner==null||!owner.isAlive()||owner.isRemoved()||owner.isSpectator()||owner.level()!=level()))discard();}
    @Override protected void addAdditionalSaveData(CompoundTag tag){}
    @Override protected void readAdditionalSaveData(CompoundTag tag){discard();}
    @Override public boolean shouldBeSaved(){return false;}
    @Override public boolean isPickable(){return false;}
    @Override public boolean canBeCollidedWith(){return false;}
    @Override public boolean canUsePortal(boolean allow){return false;}
}
