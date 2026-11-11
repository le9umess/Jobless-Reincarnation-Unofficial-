package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.npc.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.EventHooks;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Bounded server sweep. An adept drill continues through at most three successfully struck entities. */
public final class StoneCannonProjectile extends Projectile implements GeoEntity {
    private static final EntityDataAccessor<Integer> FORM_STAGE = SynchedEntityData.defineId(StoneCannonProjectile.class, EntityDataSerializers.INT);
    public static final int LIFETIME_TICKS=24;
    public static final double MAX_DISTANCE=36.0;
    private static final double EPSILON=1.0E-8;
    private static final double MAX_STEP_SQUARED=16.0;
    private final AnimatableInstanceCache animationCache=GeckoLibUtil.createInstanceCache(this);
    private float damage=8.0F;
    private double extraKnockback=.35;
    private double travelled;
    private boolean resolved;
    private boolean resolving;
    private final java.util.Set<java.util.UUID> contacts = new java.util.HashSet<>();
    private final java.util.Set<java.util.UUID> splashed = new java.util.HashSet<>();
    public StoneCannonProjectile(EntityType<? extends StoneCannonProjectile> type,Level level){super(type,level);}
    public StoneCannonProjectile(Level level,LivingEntity caster){
        this(ModContent.STONE_CANNON.get(),level);setOwner(caster);
        setPos(caster.getX(),caster.getEyeY()-.1,caster.getZ());
        setPower(MagicConfig.STONE_CANNON_DAMAGE.get().floatValue(),MagicConfig.STONE_CANNON_EXTRA_KNOCKBACK.get());
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(FORM_STAGE, 0); }
    public int formStage() { return entityData.get(FORM_STAGE); }
    public void setFormStage(int stage) { entityData.set(FORM_STAGE, Math.clamp(stage, 0, 2)); }
    @Override public boolean canUsePortal(boolean allowPassengers){return false;}
    @Override public AnimatableInstanceCache getAnimatableInstanceCache(){return animationCache;}
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers){}
    public float damage() { return damage * AttackSpellRules.damageMultiplier(formStage()); }
    public double travelled(){return travelled;}
    public void setPower(float amount,double knockback){
        damage=Float.isFinite(amount)?Math.clamp(amount,0,1000):0;
        extraKnockback=Double.isFinite(knockback)?Math.clamp(knockback,0,2):0;
    }
    @Override public void addAdditionalSaveData(CompoundTag tag){super.addAdditionalSaveData(tag);tag.putBoolean("ExpiredStone",true);tag.putInt("FormStage",formStage());}
    @Override public void readAdditionalSaveData(CompoundTag tag){super.readAdditionalSaveData(tag);setFormStage(tag.getInt("FormStage"));resolved=true;}
    private static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    private boolean loaded(Vec3 from,Vec3 to){
        AABB area=new AABB(from,to).inflate(1.0);
        for(int x=(int)Math.floor(area.minX)>>4;x<=((int)Math.floor(area.maxX)>>4);x++)
            for(int z=(int)Math.floor(area.minZ)>>4;z<=((int)Math.floor(area.maxZ)>>4);z++)if(!level().hasChunk(x,z))return false;
        return true;
    }
    private static boolean dissolves(FluidState f){return f.is(FluidTags.WATER)||f.is(FluidTags.LAVA);}
    private boolean inFluid(Vec3 point){var pos=BlockPos.containing(point);var f=level().getFluidState(pos);
        if(!dissolves(f))return false;var local=point.subtract(Vec3.atLowerCornerOf(pos));
        return f.getShape(level(),pos).toAabbs().stream().anyMatch(box->box.contains(local));}
    private BlockHitResult fluidHit(Vec3 from,Vec3 to){return level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,this){
        @Override public VoxelShape getBlockShape(BlockState state,BlockGetter world,BlockPos pos){return Shapes.empty();}
        @Override public VoxelShape getFluidShape(FluidState state,BlockGetter world,BlockPos pos){return dissolves(state)?state.getShape(world,pos):Shapes.empty();}
    });}
    private record Contact(HitResult hit, boolean dissolved) {}

    private Contact nextContact(Vec3 from, Vec3 to) {
        BlockHitResult solid = level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        BlockHitResult fluid = fluidHit(from,to);
        HitResult hit = solid.getType()==HitResult.Type.MISS ? null : solid;
        boolean dissolved = false;
        double nearest = hit==null ? Double.POSITIVE_INFINITY : from.distanceToSqr(hit.getLocation());
        if (fluid.getType()!=HitResult.Type.MISS && from.distanceToSqr(fluid.getLocation())<=nearest+EPSILON) {
            hit=fluid; nearest=from.distanceToSqr(fluid.getLocation()); dissolved=true;
        }
        for (Entity target : level().getEntities(this,new AABB(from,to).inflate(1),
                entity -> canHitEntity(entity) && !contacts.contains(AttackSpellImpact.body(entity).getUUID()))) {
            AABB bounds=target.getBoundingBox().inflate(getBbWidth()*.5);
            var crossing=bounds.clip(from,to);
            Vec3 point=bounds.contains(from)?from:crossing.orElse(null);
            if (point==null) continue;
            double at=from.distanceToSqr(point);
            if (at+EPSILON<nearest) { hit=new EntityHitResult(target,point);nearest=at;dissolved=false; }
        }
        return hit==null ? null : new Contact(hit,dissolved);
    }

    @Override public void tick() {
        if (resolving || isRemoved()) return;
        if (resolved) { discard();return; }
        resolving = true;
        try {
            Vec3 from=position(),motion=getDeltaMovement();
            double remaining=MAX_DISTANCE-travelled;
            if (!finite(from) || !finite(motion) || motion.lengthSqr()>MAX_STEP_SQUARED || !Double.isFinite(travelled)
                    || tickCount>=LIFETIME_TICKS || remaining<=EPSILON) { discard();return; }
            if (motion.length()>remaining) motion=motion.scale(remaining/motion.length());
            Vec3 to=from.add(motion);
            if (!finite(to) || !level().isClientSide && !loaded(from,to)) { discard();return; }
            super.tick();
            if (isRemoved()) return;
            if (level() instanceof ServerLevel) {
                if (inFluid(from)) { finish(from,0,contacts.size());return; }
                Vec3 cursor=from;
                // Every iteration consumes a new physical entity or terminates at a solid/fluid.
                for (int step=0;step<3;step++) {
                    if (!loaded(cursor,to)) { discard();return; }
                    Contact contact=nextContact(cursor,to);
                    if (contact==null) break;
                    HitResult hit=contact.hit();
                    int index=contacts.size();
                    if (contact.dissolved() || EventHooks.onProjectileImpact(this,hit)) {
                        finish(hit.getLocation(),0,index);return;
                    }
                    if (isRemoved()) return;
                    boolean pierced = false;
                    if (hit instanceof EntityHitResult entityHit) {
                        contacts.add(AttackSpellImpact.body(entityHit.getEntity()).getUUID());
                        pierced = damageTarget(entityHit.getEntity());
                    }
                    float radius=AttackSpellImpact.splash(this,hit,SpellCatalog.STONE_CANNON,formStage(),damage(),splashed);
                    if (isRemoved()) return;
                    if (!(hit instanceof EntityHitResult) || !pierced || contacts.size()>=AttackSpellRules.stoneContacts(formStage())) {
                        finish(hit.getLocation(),radius,index);return;
                    }
                    impact(hit.getLocation(),radius,index);
                    cursor=hit.getLocation();
                }
            }
            travelled+=motion.length();setPos(to);updateRotation();
            if (level().isClientSide && tickCount>=StoneTrail.FIRST_TICK) StoneTrail.emit(level(),to,motion,tickCount,formStage());
            if (level().isClientSide && tickCount%3==0 && motion.lengthSqr()>1.0E-8) {
                Vec3 behind=to.subtract(motion.normalize().scale(.2));
                level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK,Blocks.STONE.defaultBlockState()),
                        behind.x,behind.y,behind.z,-motion.x*.008,-.012,-motion.z*.008);
            }
            if (travelled>=MAX_DISTANCE-EPSILON || tickCount>=LIFETIME_TICKS) discard();
        } finally { resolving=false; }
    }
    private boolean damageTarget(Entity hit){
        Entity owner=getOwner();
        Entity body=hit instanceof PartEntity<?> part?part.getParent():hit;
        if(!(owner instanceof LivingEntity caster)||!(body instanceof LivingEntity target)||target instanceof ArmorStand
                ||!caster.isAlive()||caster.isRemoved()||caster.isSpectator()||caster.level()!=level()
                ||!target.isAlive()||target.isRemoved()||target.isSpectator()||target.level()!=level()||target==caster)return false;
        if(target instanceof RoxyTutorEntity||target instanceof SylphietteEntity)return false;
        if(!dev.joblessreincarnation.encounter.PaulDuel.mayAffect(caster,target))return false;
        if(target instanceof FamilyResidentEntity&&!dev.joblessreincarnation.encounter.PaulDuel.spellTarget(caster,target))return false;
        if(caster instanceof Player attacker&&target instanceof Player victim){
            if(!attacker.canHarmPlayer(victim))return false; // Respect the team friendly-fire setting for player duels.
        } else if(caster.isAlliedTo(target)||target.isAlliedTo(caster))return false;
        if(target instanceof OwnableEntity pet&&caster.getUUID().equals(pet.getOwnerUUID()))return false;
        float health=target.getHealth(),absorption=target.getAbsorptionAmount();
        float practiceScore=dev.joblessreincarnation.encounter.PaulDuel.score(target);
        boolean accepted=hit.hurt(damageSources().thrown(this,caster),damage());
        boolean loss=accepted&&(target.getHealth()<health||target.getAbsorptionAmount()<absorption
                ||practiceScore>=0&&dev.joblessreincarnation.encounter.PaulDuel.score(target)<practiceScore);
        Vec3 direction=getDeltaMovement();double flat=direction.x*direction.x+direction.z*direction.z;
        if(loss&&extraKnockback>0&&target.isAlive()&&!target.isRemoved()&&target.level()==level()&&flat>=1.0E-5){
            // hurt already supplies vanilla knockback. This is an additional, resisted/event-aware impulse.
            target.knockback(extraKnockback,-direction.x,-direction.z);
        }
        return loss;
    }
    private void impact(Vec3 point,float radius,int index) {
        if (level() instanceof ServerLevel server) {
            AttackSpellVisuals.send(server, new SpellBurstOptions(SpellBurstOptions.STONE_IMPACT,
                    getOwner()==null?-1:getOwner().getId(),formStage(),radius,index),point.x,point.y,point.z,1,0,0,0,0);
            server.playSound(null,point.x,point.y,point.z,SoundEvents.STONE_BREAK,SoundSource.PLAYERS,.4F,1.2F);
        }
    }
    private void finish(Vec3 point,float radius,int index) {
        resolved=true;setPos(point);impact(point,radius,index);discard();
    }
}
