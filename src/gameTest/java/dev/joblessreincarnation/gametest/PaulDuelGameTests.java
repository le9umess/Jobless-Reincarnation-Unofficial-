package dev.joblessreincarnation.gametest;

import com.mojang.authlib.GameProfile;
import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.advancement.StoryAdvancements;
import dev.joblessreincarnation.compat.epic.PaulDuelCombat;
import dev.joblessreincarnation.encounter.PaulDuel;
import dev.joblessreincarnation.encounter.PaulDuelArena;
import dev.joblessreincarnation.encounter.PaulDuelProgress;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Actor;
import dev.joblessreincarnation.gametest.SwordLessonGameTests.Fixture;
import dev.joblessreincarnation.magic.SpellCatalog;
import dev.joblessreincarnation.magic.SpellCasting;
import dev.joblessreincarnation.network.BossBarPayload;
import dev.joblessreincarnation.network.RemoveBossBarPayload;
import dev.joblessreincarnation.npc.FamilyResidentEntity;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import dev.joblessreincarnation.training.SwordTrainingProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

@GameTestHolder(JoblessReincarnation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PaulDuelGameTests {
    private static final Vec3 PAUL=new Vec3(16.5,2,16.5), PLAYER=PAUL.add(0,0,-2);
    private static class WatchingActor extends Actor {
        final List<CustomPacketPayload> payloads=new ArrayList<>();
        WatchingActor(GameTestHelper h,GameProfile profile){
            super(h,profile);var previous=connection;
            connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),this,CommonListenerCookie.createInitial(profile,false)){
                @Override public void send(Packet<?> packet){capturePayload(packet);previous.send(packet);}
                @Override public void send(Packet<?> packet,@Nullable PacketSendListener listener){capturePayload(packet);previous.send(packet,listener);}
            };
        }
        private void capturePayload(Packet<?> p){if(p instanceof ClientboundCustomPayloadPacket c)payloads.add(c.payload());}
    }
    private record Setup(Fixture fixture,WatchingActor player,WatchingActor peer,FamilyResidentEntity paul){}
    private static WatchingActor actor(Fixture f,Vec3 at){
        WatchingActor p=new WatchingActor(f.helper,new GameProfile(UUID.randomUUID(),"P01"+UUID.randomUUID().toString().substring(0,8)));
        f.actors.add(p);p.connect();p.setPos(f.helper.absoluteVec(at));p.setOnGround(true);p.sword();
        for(int i=0;i<61;i++)p.serverLevel().tickNonPassenger(p);
        p.setPos(f.helper.absoluteVec(at));p.setOnGround(true);p.setYRot(0);p.setYHeadRot(0);p.setYBodyRot(0);
        return p;
    }
    private static Setup setup(GameTestHelper h){
        Fixture f=new Fixture(h);
        try{
            WatchingActor p=actor(f,PLAYER),peer=actor(f,PLAYER.add(2,0,0));
            var paul=f.paul(PAUL);paul.setNoGravity(false);paul.setOnGround(true);
            h.assertTrue(PaulDuelCombat.patch(paul)!=null,"Paul has the real dedicated Epic Fight patch");
            return new Setup(f,p,peer,paul);
        }catch(RuntimeException|Error e){f.close();throw e;}
    }
    private static void recognize(Actor p){
        CompoundTag n=new CompoundTag();n.putBoolean("started",true);n.putInt("hits",20);n.putBoolean("recognized",true);
        p.setData(ModAttachments.SWORD_TRAINING,SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE,n).getOrThrow());
    }
    private static void click(Actor player,FamilyResidentEntity paul,boolean crouch){
        player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(paul,crouch,InteractionHand.MAIN_HAND,new Vec3(0,1,0)));
    }
    private static void after(Setup s,int delay,Runnable run){s.fixture.helper.runAfterDelay(delay,()->s.fixture.checked(run));}
    private static String arenaDiagnostic(Setup s){
        var level=(net.minecraft.server.level.ServerLevel)s.paul.level();BlockPos origin=s.paul.blockPosition();
        StringBuilder out=new StringBuilder("paul=").append(s.paul.position()).append(" ground=").append(s.paul.onGround())
                .append(" floor=").append(level.getBlockState(origin.below())).append(" ready=")
                .append(dev.joblessreincarnation.npc.NpcLoadedChunks.ready(level,new net.minecraft.world.phys.AABB(origin).inflate(9,6,9)));
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(x*x+z*z<=64){
            BlockPos feet=origin.offset(x,0,z),floor=feet.below();var ground=level.getBlockState(floor);
            if(!ground.isFaceSturdy(level,floor,net.minecraft.core.Direction.UP)||ground.is(Blocks.FARMLAND))return out.append(" bad floor ").append(floor).append('=').append(ground).toString();
            for(int y=0;y<5;y++){BlockPos p=feet.above(y);var bs=level.getBlockState(p);if(!bs.getCollisionShape(level,p).isEmpty())return out.append(" obstacle ").append(p).append('=').append(bs).toString();}
        }
        return out.append(" messages=").append(s.player.messages.stream().map(x->x.content().getString()).toList()).toString();
    }
    private static void done(Setup s){s.fixture.close();s.fixture.helper.succeed();}
    private static void begin(Setup s,BiConsumer<Setup,PaulDuel.Bout> check){
        recognize(s.player);click(s.player,s.paul,true);click(s.player,s.paul,true);
        s.fixture.helper.assertTrue(PaulDuel.bout(s.paul)==null,"One same-tick input cannot confirm its own invitation");
        after(s,6,()->{
            click(s.player,s.paul,true);
            s.fixture.helper.assertTrue(PaulDuel.bout(s.paul)!=null,"Second real interaction starts the encounter in the open arena; "+arenaDiagnostic(s));
            after(s,65,()->{
                PaulDuel.Bout b=PaulDuel.bout(s.paul);
                s.fixture.helper.assertTrue(b!=null&&b.active(),"Intro completes before actual combat begins");
                check.accept(s,b);
            });
        });
    }
    private static void phaseTwo(Setup s,PaulDuel.Bout b,Runnable then){
        s.player.sword();s.player.setPos(s.fixture.helper.absoluteVec(PLAYER));s.paul.invulnerableTime=0;
        s.fixture.helper.assertTrue(s.paul.hurt(s.player.damageSources().playerAttack(s.player),1000),"Accepted oversized native hit");
        s.fixture.helper.assertTrue(b.stage==PaulDuel.Stage.INTERMISSION&&b.phase==1,"First bar changes to the intermission");
        s.fixture.helper.assertTrue(b.remaining==80&&s.paul.getHealth()==20,"No overflow into the new bar or damage to the story NPC");
        after(s,81,()->{s.fixture.helper.assertTrue(b.active()&&b.phase==1,"Second bar starts only after the timed intermission");then.run();});
    }
    @GameTest(template="p01_arena",timeoutTicks=160)
    public static void nativeInvitationKeepsFirstLessonAndOwnership(GameTestHelper h){
        Setup s=setup(h);
        s.fixture.checked(()->{
            click(s.player,s.paul,true);h.assertTrue(PaulDuel.bout(s.paul)==null,"First lesson is required");
            click(s.player,s.paul,false);h.assertTrue(s.player.getData(ModAttachments.SWORD_TRAINING).started(),"Ordinary wooden-sword consultation still starts the existing lesson");
            begin(s,(x,b)->{
                recognize(s.peer);click(s.peer,s.paul,true);
                h.assertTrue(PaulDuel.opponent(s.paul)==s.player&&PaulDuel.forPlayer(s.peer)==null,"Bystander cannot replace the enrolled challenger");
                h.assertTrue(s.player.payloads.stream().anyMatch(p->p instanceof BossBarPayload v&&v.participant()),"Challenger receives own full HUD state");
                h.assertTrue(s.peer.payloads.stream().anyMatch(p->p instanceof BossBarPayload v&&!v.participant()),"Nearby observer sees the state without being enrolled");
                done(s);
            });
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=160)
    public static void firstBarBlocksMagicBeforePaymentAndAcceptsNormalSword(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            float mana=s.player.getData(ModAttachments.MANA).mana();
            s.player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
            s.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModContent.TRAINING_STAFF.get()));
            h.assertTrue(!SpellCasting.canBegin(s.player),"First bar denies starting a spell");
            h.assertTrue(s.player.getData(ModAttachments.MANA).mana()==mana&&s.player.getData(ModAttachments.CASTING).activeSpell()==null,"No payment or active chant is created");
            s.player.sword();s.player.recover();float before=b.remaining;
            s.player.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(s.paul,false));
            h.assertTrue(b.remaining<before&&s.paul.getHealth()==20,"Ordinary native sword attack lowers the bar without changing Paul health");
            float score=b.remaining;s.peer.sword();s.peer.setPos(s.player.position().add(.3,0,0));s.paul.invulnerableTime=0;
            h.assertTrue(!s.paul.hurt(s.peer.damageSources().playerAttack(s.peer),100)&&b.remaining==score,"Observer cannot contribute damage");
            done(s);
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=260)
    public static void stageChangeConsumesOneHitAndSecondBarCanStaySwordOnly(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            phaseTwo(s,b,()->{
                s.player.sword();s.paul.invulnerableTime=0;
                h.assertTrue(s.paul.hurt(s.player.damageSources().playerAttack(s.player),1000),"A sword remains valid in second phase");
                h.assertTrue(b.stage==PaulDuel.Stage.RESULT&&b.remaining==0,"Second completed bar wins");
                h.assertTrue(s.player.getData(ModAttachments.PAUL_DUEL_PROGRESS).won()&&!s.peer.getData(ModAttachments.PAUL_DUEL_PROGRESS).won(),"Victory is a private per-player fact");
                h.assertTrue(StoryAdvancements.hasCompleted(s.player,StoryAdvancements.PAUL_DUEL),"The native advancement is awarded");
                h.assertTrue(s.paul.isAlive()&&s.paul.getHealth()==20,"Paul remains an intact story NPC");
                done(s);
            });
            h.assertTrue(!SpellCasting.canBegin(s.player),"Intermission is not an early spell window");
            float health=s.player.getHealth();
            h.assertTrue(!s.player.hurt(s.paul.damageSources().mobAttack(s.paul),100)&&s.player.getHealth()==health,"Old attack cannot hit while Paul is speaking");
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=150)
    public static void paulDamageStopsAtFourHealthWithoutHealing(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.player.setHealth(11);s.player.invulnerableTime=0;
            s.player.hurt(s.paul.damageSources().mobAttack(s.paul),1000);
            h.assertTrue(s.player.isAlive()&&s.player.getHealth()==4,"A lethal Paul hit stops at exactly four health");
            h.assertTrue(b.stage==PaulDuel.Stage.RESULT&&!s.player.getData(ModAttachments.PAUL_DUEL_PROGRESS).won(),"This is a loss, not a victory");
            float health=s.player.getHealth();s.player.hurt(s.paul.damageSources().mobAttack(s.paul),1000);
            h.assertTrue(s.player.getHealth()==health,"Late follow-up cannot finish the challenger");
            after(s,62,()->{h.assertTrue(s.player.getHealth()==4&&PaulDuel.bout(s.paul)==null,"Closing does not grant free recovery");done(s);});
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=160)
    public static void realNativeAttackHasWindupAndOnlyHitsParticipant(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.peer.setPos(s.player.position().add(.2,0,0));
            PaulDuel.play(b,PaulDuel.Move.STEP);
            after(s,7,()->h.assertTrue(s.player.getHealth()==20&&s.peer.getHealth()==20,"No hit during the visible startup"));
            after(s,23,()->{
                h.assertTrue(s.player.getHealth()<20,"Native registered sword phase makes a real contact; move="+b.move+" elapsed="+PaulDuelCombat.elapsed(s.paul,PaulDuel.Move.STEP)+" pos="+s.paul.position()+" target="+s.player.position()+" yaw="+s.paul.getYRot());
                h.assertTrue(s.peer.getHealth()==20,"The same native sweep cannot hit the nearby spectator");done(s);
            });
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=300)
    public static void leavingAbandonsAndNpcReturnsToProtection(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.player.setPos(b.arena.centre().add(b.arena.radius()+2,0,0));
            after(s,2,()->h.assertTrue(b.active()&&b.outside>=0,"Stepping out starts the countdown instead of ending the bout"));
            after(s,PaulDuel.LEAVE_GRACE+2,()->{
                h.assertTrue(b.stage==PaulDuel.Stage.RESULT&&!s.player.getData(ModAttachments.PAUL_DUEL_PROGRESS).won(),"Staying out past the countdown is an unrewarded abandonment");
                after(s,62,()->{
                    h.assertTrue(PaulDuel.bout(s.paul)==null&&s.paul.duelStance()==0,"Encounter and public combat stance end");
                    s.paul.invulnerableTime=0;h.assertTrue(!s.paul.hurt(s.peer.damageSources().playerAttack(s.peer),1000),"Ordinary NPC protection is intact afterwards");done(s);
                });
            });
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=300)
    public static void steppingOutAndBackWithinTheCountdownKeepsFighting(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            Vec3 inside=s.player.position();
            s.player.setPos(b.arena.centre().add(b.arena.radius()+2,0,0));
            after(s,40,()->{
                h.assertTrue(b.active()&&b.outside>=0,"Still counting down while outside");
                s.player.setPos(inside);
                after(s,2,()->{
                    h.assertTrue(b.active()&&b.outside<0,"Coming back cancels the countdown");
                    after(s,PaulDuel.LEAVE_GRACE,()->{h.assertTrue(PaulDuel.bout(s.paul)==b&&b.active(),"The bout simply goes on");done(s);});
                });
            });
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=140)
    public static void logoutClearsSessionAndViewerBar(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.player.close();
            h.assertTrue(PaulDuel.bout(s.paul)==null&&s.paul.duelStance()==0,"Logout immediately clears this encounter");
            h.assertTrue(s.peer.payloads.stream().anyMatch(p->p instanceof RemoveBossBarPayload v&&v.id().equals(b.id)),"Observer receives a removal, not an orphaned bar");done(s);
        });
    }
    @GameTest(template="p01_home_approach",timeoutTicks=790)
    public static void walksFromFurnishedHomeToNearbyClearingWithoutEditingIt(GameTestHelper h){
        Setup s=setup(h);s.fixture.checked(()->{
            // A covered home occupies the first half; the reachable open ring is beyond the old twelve-block search.
            Vec3 start=h.absoluteVec(new Vec3(32.5,2,32.5));
            s.paul.setPos(start);s.paul.setNoAi(false);s.paul.setOnGround(true);s.paul.reanchor();
            var home=s.paul.getHomeAnchor();
            s.player.setPos(start.add(0,0,-2));recognize(s.player);click(s.player,s.paul,true);
            after(s,6,()->{
                click(s.player,s.paul,true);PaulDuel.Bout b=PaulDuel.bout(s.paul);
                h.assertTrue(b!=null&&b.arena.centre().distanceTo(start)>12,"Finds the adjoining clearing, not a ring under the roof");
                s.player.setPos(b.arena.centre().add(0,0,-2));
                after(s,340,()->{
                    h.assertTrue(PaulDuel.bout(s.paul)==b&&b.active()&&s.paul.distanceToSqr(start)>144,"Paul genuinely navigates out before combat starts; stage="+b.stage+" current="+(PaulDuel.bout(s.paul)==b)+" pos="+s.paul.position()+" centre="+b.arena.centre()+" path="+s.paul.getNavigation().isDone()+" hp="+s.player.getHealth()+" elapsed="+s.paul.distanceToSqr(start));
                    h.assertTrue(home.equals(s.paul.getHomeAnchor()),"Practice never moves the resident's saved home");
                    h.assertTrue(h.getBlockState(new BlockPos(46,5,32)).is(Blocks.OAK_PLANKS),"The roof is preserved");
                    s.player.setPos(b.arena.centre().add(b.arena.radius()+2,0,0));
                    after(s,300+PaulDuel.LEAVE_GRACE,()->{
                        h.assertTrue(PaulDuel.bout(s.paul)==null&&s.paul.distanceToSqr(Vec3.atBottomCenterOf(home.pos()))<=25,"After abandonment Paul actually walks back into his unchanged home circle; pos="+s.paul.position()+" home="+home);
                        done(s);
                    });
                });
            });
        });
    }
    @GameTest(template="p01_home_approach")
    public static void dryIrrigationCrossingDoesNotChangeWaterAvoidance(GameTestHelper h){
        Setup s=setup(h);s.fixture.checked(()->{
            for(int x=0;x<65;x++)s.fixture.block(new BlockPos(x,1,32),
                    (x==32?Blocks.DIRT_PATH:Blocks.WATER).defaultBlockState());
            s.paul.setPos(h.absoluteVec(new Vec3(32.5,2,42.5)));s.paul.setOnGround(true);
            float bank=s.paul.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER);
            float water=s.paul.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER);
            var path=PaulDuelArena.route(s.paul,h.absolutePos(new BlockPos(32,2,22)));
            h.assertTrue(path!=null&&path.canReach(),"The dry path across an irrigation ditch is reachable");
            boolean crossed=false;
            for(int i=0;i<path.getNodeCount();i++){
                BlockPos p=path.getNode(i).asBlockPos();
                h.assertTrue(h.getLevel().getFluidState(p).isEmpty()&&h.getLevel().getFluidState(p.below()).isEmpty(),"The native route stays on dry support, not in the canal");
                if(p.equals(h.absolutePos(new BlockPos(32,2,32))))crossed=true;
            }
            h.assertTrue(crossed,"The path really uses the one-block bridge");
            h.assertTrue(s.paul.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER)==bank
                    &&s.paul.getPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER)==water,"Both original avoidance costs remain intact after planning");
            done(s);
        });
    }
    @GameTest(template="p01_arena")
    public static void invitationFromDirtPathUsesTheStandingCell(GameTestHelper h){
        Setup s=setup(h);s.fixture.checked(()->{
            s.fixture.block(new BlockPos(16,1,16),Blocks.DIRT_PATH.defaultBlockState());
            s.paul.setPos(h.absoluteVec(PAUL.add(0,-1.0/16,0)));s.paul.setOnGround(true);
            var arena=PaulDuelArena.find(s.paul);
            h.assertTrue(arena!=null&&arena.centre().y==h.absoluteVec(PAUL).y,"Standing on the sunken garden path can still find a neighbouring full-height clearing");done(s);
        });
    }
    @GameTest(template="p01_arena")
    public static void arenaPreflightCannotReuseAnUnrelatedStroll(GameTestHelper h){
        Setup s=setup(h);s.fixture.checked(()->{
            for(int i=13;i<=19;i++)for(BlockPos p:new BlockPos[]{new BlockPos(13,2,i),new BlockPos(19,2,i),new BlockPos(i,2,13),new BlockPos(i,2,19)})
                s.fixture.block(p,Blocks.OAK_FENCE.defaultBlockState());
            var stroll=s.paul.getNavigation().createPath(h.absolutePos(new BlockPos(18,2,16)),0);
            h.assertTrue(stroll!=null&&stroll.canReach()&&s.paul.getNavigation().moveTo(stroll,.6),"An unrelated short stroll is currently in progress inside the enclosure");
            h.assertTrue(PaulDuelArena.find(s.paul)==null,"An unreachable exterior clearing cannot borrow the reachable stroll path");
            done(s);
        });
    }
    @GameTest(template="p01_arena")
    public static void arenaRejectsFarmAndBuildingWithoutEditingEither(GameTestHelper h){
        Setup s=setup(h);s.fixture.checked(()->{
            BlockPos at=h.absolutePos(BlockPos.containing(PAUL));
            h.assertTrue(PaulDuelArena.clear(h.getLevel(),at,8),"A genuinely flat loaded clearing is valid; "+arenaDiagnostic(s));
            s.fixture.block(new BlockPos(16,1,17),Blocks.FARMLAND.defaultBlockState());
            h.assertTrue(!PaulDuelArena.clear(h.getLevel(),at,8),"Farm soil cannot become the arena floor");
            h.assertTrue(h.getBlockState(new BlockPos(16,1,17)).is(Blocks.FARMLAND),"Preflight never flattens a farm");
            s.fixture.block(new BlockPos(16,1,17),Blocks.STONE.defaultBlockState());
            s.fixture.block(new BlockPos(16,5,17),Blocks.OAK_PLANKS.defaultBlockState());
            h.assertTrue(!PaulDuelArena.clear(h.getLevel(),at,8),"A roof excludes a building interior");done(s);
        });
    }
    /**
     * Arm Strike against one of Paul's cuts, pressed {@code press} ticks into it, with Paul's or the player's Epic Fight tick
     * first in each server tick (the level ticks entities in the order they joined, so both happen). Returns one line of what
     * happened; a cut that still hurts the player after it was interrupted, or an interruption shown a different number of
     * times than it happened, goes into {@code failures}.
     */
    private static String armTrial(Setup s,PaulDuel.Bout b,PaulDuel.Move move,int press,boolean paulFirst,List<String> failures){
        var patch=yesman.epicfight.world.capabilities.EpicFightCapabilities.getServerPlayerPatch(s.player);
        var slot=patch.getSkill(dev.joblessreincarnation.compat.epic.SchoolSkills.Slot.JOBLESS_PREPARED_FORM);
        var strike=dev.joblessreincarnation.compat.epic.SchoolAnimations.ARM_STRIKE;
        s.player.setHealth(20);s.player.invulnerableTime=0;s.player.hurtTime=0;patch.setStamina(patch.getMaxStamina());
        b.remaining=PaulDuel.FIRST_BAR;
        s.player.setPos(s.paul.position().add(0,0,-1.5));s.player.setYRot(0);s.player.setYHeadRot(0);s.player.setYBodyRot(0);patch.setModelYRot(0,true);
        PaulDuel.play(b,move);
        long shown=s.player.spellBursts.stream().filter(c->c.kind()==dev.joblessreincarnation.magic.SpellBurstOptions.ARM_BREAK).count();
        StringBuilder out=new StringBuilder(move+" press@"+press+(paulFirst?" paul-first":" player-first")+":");
        float hp=s.player.getHealth(),score=b.remaining,contactAt=-1;int cast=-1,contact=-1,stagger=-1;Vec3 start=null;
        for(int t=0;t<36;t++){
            if(t==press){
                if(slot.requestCasting(patch,new CompoundTag())){cast=t;start=s.player.position();}else out.append(" refused@").append(t);
            }
            PaulDuel.Move was=b.move;float at=was==null?-1:PaulDuelCombat.elapsed(s.paul,was);
            final int i=t-cast;final Vec3 from=start;final boolean striking=cast>=0;
            Runnable player=()->{
                if(striking&&patch.getAnimator().getPlayerFor(null).getRealAnimation()==strike){
                    Vec3 old=s.player.position(),step=strike.get().getExpectedMovement(patch,(i+1)*.05F);
                    s.player.setPos(from.add(step));s.player.xOld=old.x;s.player.yOld=old.y;s.player.zOld=old.z;
                }
                patch.getAnimator().tick();patch.updateEntityState();
            };
            if(paulFirst){advance(s.paul,1);player.run();}else{player.run();advance(s.paul,1);}
            if(contact<0&&b.remaining<score){contact=t;contactAt=paulFirst?at+.05F:at;out.append(String.format(" contact@%d(paul %s %.2f)",t,was,at));}
            if(stagger<0&&(b.move==PaulDuel.Move.STAGGER||b.move==PaulDuel.Move.STAGGER2)){stagger=t;out.append(" stagger@").append(t);}
            float now=s.player.getHealth();
            if(now<hp){
                out.append(String.format(" hurt@%d(%.1f by %s %.2f)",t,hp-now,was,at));
                if(stagger>=0&&was==move)failures.add(out+"  <- the interrupted cut still hurt");
                hp=now;
            }
        }
        var breaks=s.player.spellBursts.stream().filter(c->c.kind()==dev.joblessreincarnation.magic.SpellBurstOptions.ARM_BREAK).toList();
        long shownNow=breaks.size();
        if((shownNow-shown)!=(stagger>=0?1:0))failures.add(out+"  <- ARM_BREAK shown "+(shownNow-shown)+" times");
        if(stagger>=0&&shownNow>shown){
            int stage=breaks.get(breaks.size()-1).stage();out.append(" cue-stage=").append(stage);
            boolean later=move==PaulDuel.Move.SWEEP&&contactAt>=.68F;
            if(stage!=(later?1:0))failures.add(out+"  <- a "+(later?"later":"first")+" strike caught shows stage "+stage);
        }
        // Out of reach until the next trial, so Paul's own next move cannot touch the player in between.
        s.player.setPos(s.paul.position().add(0,0,-5));
        return out.toString();
    }
    private static void armTimeline(GameTestHelper h,PaulDuel.Move move,int lastPress){
        Setup s=setup(h);begin(s,(x,b)->{
            epic(s.player,dev.joblessreincarnation.training.SchoolForm.ARM_STRIKE);
            List<String> lines=new ArrayList<>(),failures=new ArrayList<>();
            int trials=(lastPress+1)*2;
            Runnable[] next=new Runnable[1];int[] k={0};
            next[0]=()->{
                if(k[0]==trials){
                    lines.forEach(line->com.mojang.logging.LogUtils.getLogger().info("[ArmTiming] {}",line));
                    h.assertTrue(failures.isEmpty(),String.join(" | ",failures));
                    h.assertTrue(lines.stream().anyMatch(l->l.contains("stagger@")),"Some press really interrupts "+move);
                    done(s);return;
                }
                int press=k[0]/2;boolean paulFirst=k[0]%2==0;k[0]++;
                lines.add(armTrial(s,b,move,press,paulFirst,failures));
                after(s,16,next[0]);
            };
            next[0].run();
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=520) public static void armStrikeTimelineAgainstStep(GameTestHelper h){armTimeline(h,PaulDuel.Move.STEP,12);}
    @GameTest(template="p01_arena",timeoutTicks=400) public static void armStrikeTimelineAgainstFast(GameTestHelper h){armTimeline(h,PaulDuel.Move.FAST,8);}
    @GameTest(template="p01_arena",timeoutTicks=700) public static void armStrikeTimelineAgainstSweep(GameTestHelper h){armTimeline(h,PaulDuel.Move.SWEEP,17);}
    /** Promo footage 11-08: a player standing still 1.76 blocks off was never attacked. */
    @GameTest(template="p01_arena",timeoutTicks=200)
    public static void paulStepsInOnAPlayerWhoStandsStill(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.player.setInvulnerable(true);
            s.player.setPos(s.paul.position().add(0,0,-2.6));
            long placed=s.paul.level().getGameTime();
            int[] waited={0};
            Runnable[] check=new Runnable[1];
            check[0]=()->{
                double gap=Math.sqrt(s.paul.distanceToSqr(s.player));
                // Only a swing begun after the player was put 2.6 blocks off counts; one already under way may finish first.
                if(b.move!=null&&b.move.attack()&&b.moveStarted>placed){
                    h.assertTrue(gap<=1.75,"Paul swings only once he is inside his reach; gap="+gap);done(s);return;
                }
                if(++waited[0]>60)h.fail("Paul never swung at a player standing 2.6 blocks off; gap="+gap+" move="+b.move);
                after(s,1,check[0]);
            };
            after(s,1,check[0]);
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=120)
    public static void paulsLastStepsFollowHisSlows(GameTestHelper h){
        Setup s=setup(h);s.fixture.checked(()->{
            var speed=s.paul.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            h.assertTrue(Math.abs(PaulDuel.approachStep(s.paul)-PaulDuel.APPROACH_STEP)<1.0E-9,"Full pace without a slow");
            var slow=new net.minecraft.world.entity.ai.attributes.AttributeModifier(dev.joblessreincarnation.magic.MireFields.SLOW_ID,-.5,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            speed.addTransientModifier(slow);
            h.assertTrue(Math.abs(PaulDuel.approachStep(s.paul)-PaulDuel.APPROACH_STEP*.5)<1.0E-9,"A slow to half speed halves the last steps too");
            speed.removeModifier(slow.id());
            done(s);
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=260)
    public static void edgeWarningIsSaidOncePerApproach(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.player.setInvulnerable(true);
            Vec3 centre=b.arena.centre();double r=b.arena.radius();
            java.util.function.Supplier<Long> said=()->s.player.messages.stream().filter(m->m.content().getContents()
                    instanceof net.minecraft.network.chat.contents.TranslatableContents t&&t.getKey().equals("duel.jobless_reincarnation.paul.edge")).count();
            long before=said.get();
            s.player.setPos(centre.add(r-.6,0,0));
            after(s,40,()->{
                h.assertValueEqual(said.get()-before,1L,"Staying at the edge is warned once, not every second");
                s.player.setPos(centre);
                after(s,10,()->{
                    s.player.setPos(centre.add(0,0,r-.6));
                    after(s,30,()->{
                        h.assertValueEqual(said.get()-before,2L,"Coming back to the edge warns again");
                        done(s);
                    });
                });
            });
        });
    }
    private static void epic(Actor player,dev.joblessreincarnation.training.SchoolForm form){
        var data=new CompoundTag();data.putBoolean("started",true);data.putInt("hits",20);data.putBoolean("recognized",true);
        data.putBoolean("aura_drill",true);data.putBoolean("aura_awakened",true);data.putInt("school_practices",7);data.putInt("school_forms",7);
        player.setData(ModAttachments.SWORD_TRAINING,SwordTrainingProgress.CODEC.parse(NbtOps.INSTANCE,data).getOrThrow());
        player.setShiftKeyDown(false);player.setOnGround(true);player.sword();
        var patch=yesman.epicfight.world.capabilities.EpicFightCapabilities.getServerPlayerPatch(player);
        patch.toEpicFightMode(false);patch.setModelYRot(0,true);patch.setStamina(patch.getMaxStamina());
        patch.getPlayerSkills().addLearnedSkill(dev.joblessreincarnation.compat.epic.SchoolSkills.skill(form));
        if(!dev.joblessreincarnation.compat.epic.SchoolSkills.toggleFromNote(player,form))throw new AssertionError("Native skill preparation failed");
    }
    private static void advance(FamilyResidentEntity paul,int ticks){
        var patch=PaulDuelCombat.patch(paul);
        for(int i=0;i<ticks;i++){patch.getAnimator().tick();patch.updateEntityState();}
    }
    @GameTest(template="p01_arena",timeoutTicks=280)
    public static void secondPhasePaidWaterProjectileScoresOnlyForItsOwner(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->phaseTwo(s,b,()->{
            s.player.setShiftKeyDown(false);s.player.setPos(s.paul.position().add(0,0,-5.5));s.player.setYRot(0);s.player.setXRot(0);
            s.peer.setPos(s.paul.position().add(3,0,0));s.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModContent.TRAINING_STAFF.get()));
            s.player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
            float mana=s.player.getData(ModAttachments.MANA).mana(),before=b.remaining;
            h.assertTrue(SpellCasting.canBegin(s.player),"Second phase really permits the staff start gate");
            h.assertTrue(SpellCatalog.WATER_BOLT.cast(s.player,0),"The normal authoritative paid water cast succeeds");
            h.assertTrue(s.player.getData(ModAttachments.MANA).mana()<mana,"Real spell cost is paid");
            after(s,12,()->{
                h.assertTrue(b.remaining<before&&s.paul.getHealth()==20,"A naturally moving owned projectile scores without injuring the NPC");
                h.assertTrue(s.peer.getHealth()==20,"The observer is not damaged by this practice cast");done(s);
            });
        }));
    }
    @GameTest(template="p01_arena",timeoutTicks=160)
    public static void nativeArmStrikeInterruptsPaulsActualWindup(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            epic(s.player,dev.joblessreincarnation.training.SchoolForm.ARM_STRIKE);
            s.player.setPos(s.paul.position().add(0,0,-1.5));
            PaulDuel.play(b,PaulDuel.Move.STEP);advance(s.paul,4);
            var patch=yesman.epicfight.world.capabilities.EpicFightCapabilities.getServerPlayerPatch(s.player);
            float score=b.remaining,hp=s.player.getHealth();
            h.assertTrue(patch.getSkill(dev.joblessreincarnation.compat.epic.SchoolSkills.Slot.JOBLESS_PREPARED_FORM).requestCasting(patch,new CompoundTag()),"The actual learned arm-strike skill starts");
            Vec3 start=s.player.position();
            for(int i=0;i<8;i++){
                Vec3 old=s.player.position(),step=dev.joblessreincarnation.compat.epic.SchoolAnimations.ARM_STRIKE.get().getExpectedMovement(patch,(i+1)*.05F);
                s.player.setPos(start.add(step));s.player.xOld=old.x;s.player.yOld=old.y;s.player.zOld=old.z;
                patch.getAnimator().tick();advance(s.paul,1);
            }
            h.assertTrue(b.remaining<score&&b.move==PaulDuel.Move.STAGGER,"A real native arm contact interrupts the native windup");
            advance(s.paul,35);
            h.assertTrue(s.player.getHealth()==hp,"The canceled cut has no delayed contact");done(s);
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=160)
    public static void nativeParryCreatesFlowAndCounterScoresAgainstPaul(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            epic(s.player,dev.joblessreincarnation.training.SchoolForm.FLOW_COUNTER);
            s.player.setPos(s.paul.position().add(0,0,-1.5));
            var patch=yesman.epicfight.world.capabilities.EpicFightCapabilities.getServerPlayerPatch(s.player);
            var guard=patch.getSkill(yesman.epicfight.skill.SkillSlots.GUARD);guard.setSkill(yesman.epicfight.registry.entries.EpicFightSkills.PARRYING.get());
            PaulDuel.play(b,PaulDuel.Move.STEP);advance(s.paul,10);
            h.assertTrue(guard.requestHold(patch,new CompoundTag()),"Native timed front guard is held");
            float hp=s.player.getHealth(),score=b.remaining;advance(s.paul,8);
            h.assertTrue(s.player.getHealth()==hp,"The actual front parry prevents health loss");
            h.assertTrue(s.player.getData(ModAttachments.SCHOOL_COMBAT).counterTarget(s.player)==s.paul,"Paul is the real attacker in the Flow opening");
            h.assertTrue(patch.getSkill(dev.joblessreincarnation.compat.epic.SchoolSkills.Slot.JOBLESS_PREPARED_FORM).requestCasting(patch,new CompoundTag()),"The active Flow follow-up starts");
            for(int i=0;i<18;i++)patch.getAnimator().tick();
            h.assertTrue(b.remaining<score&&s.peer.getHealth()==20,"Flow contacts only the enrolled attacker");done(s);
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=250)
    public static void chargeImmunityExistsOnlyDuringForwardRush(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->phaseTwo(s,b,()->{
            s.player.setPos(s.paul.position().add(0,0,-6));PaulDuel.play(b,PaulDuel.Move.CHARGE);advance(s.paul,4);
            s.paul.setDeltaMovement(Vec3.ZERO);s.paul.knockback(1,0,1);
            h.assertTrue(s.paul.getDeltaMovement().horizontalDistanceSqr()>0,"Charge startup does not have immunity");
            s.paul.setDeltaMovement(Vec3.ZERO);advance(s.paul,9);
            h.assertTrue(PaulDuel.charging(s.paul),"Now inside authored forward rush");
            s.paul.knockback(1,0,1);PaulDuelCombat.patch(s.paul).knockBackEntity(s.player.position(),1);h.assertTrue(s.paul.getDeltaMovement().horizontalDistanceSqr()==0,"The rush resists both vanilla and direct EF knockback");
            advance(s.paul,9);s.paul.knockback(1,0,1);
            h.assertTrue(!PaulDuel.charging(s.paul)&&s.paul.getDeltaMovement().horizontalDistanceSqr()>0,"Recovery is vulnerable again");done(s);
        }));
    }
    @GameTest(template="p01_arena",timeoutTicks=280)
    public static void mireReallySlowsPaulBeforeTheTelegraphedEscape(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->phaseTwo(s,b,()->{
            s.player.setPos(s.paul.position().add(0,0,-6));s.paul.setNoAi(false);Vec3 start=s.paul.position();
            var field=new dev.joblessreincarnation.magic.MireEntity(h.getLevel(),s.player,start);
            s.fixture.admit(field,h.relativeVec(start));dev.joblessreincarnation.magic.MireFields.activate(field);
            after(s,13,()->{
                h.assertTrue(s.paul.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).hasModifier(dev.joblessreincarnation.magic.MireFields.SLOW_ID),"Real mire modifier applies before escape; eligible="+dev.joblessreincarnation.magic.MireTargets.eligible(s.player,s.paul)+" touches="+dev.joblessreincarnation.magic.MireTargets.touches(field,s.paul)+" active="+field.activeAt(h.getLevel().getGameTime())+" alive="+field.isAlive()+" ground="+s.paul.onGround()+" stage="+b.stage+" move="+b.move+" pos="+s.paul.position()+" field="+field.position());
                after(s,23,()->{
                    h.assertTrue(s.paul.position().distanceToSqr(start)>.8,"The committed stomp produces actual movement");
                    h.assertTrue(!s.paul.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).hasModifier(dev.joblessreincarnation.magic.MireFields.SLOW_ID),"Leaving the actual mire footprint releases slowing");done(s);
                });
            });
        }));
    }
    @GameTest(template="p01_arena",timeoutTicks=250)
    public static void actualWaterScreenInterceptsPaulsNativeContact(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->phaseTwo(s,b,()->{
            s.player.setPos(s.paul.position().add(0,0,-1.5));
            Vec3 base=s.player.position().add(0,0,.7);
            var shield=new dev.joblessreincarnation.magic.WaterShieldEntity(h.getLevel(),s.player,base,0);
            s.fixture.admit(shield,h.relativeVec(base));
            PaulDuel.play(b,PaulDuel.Move.FAST);float hp=s.player.getHealth();advance(s.paul,17);
            h.assertTrue(s.player.getHealth()==hp&&shield.isAlive(),"The live owned water plane stops a true native sword contact");
            shield.discard();s.player.invulnerableTime=0;PaulDuel.play(b,PaulDuel.Move.FAST);advance(s.paul,17);
            h.assertTrue(s.player.getHealth()<hp,"Without the screen the identical attack really hits");done(s);
        }));
    }
    @GameTest(template="p01_arena",timeoutTicks=250)
    public static void victorySurvivesPlayerSaveAndDoesNotResumeTheBout(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->phaseTwo(s,b,()->{
            s.player.sword();s.paul.invulnerableTime=0;s.paul.hurt(s.player.damageSources().playerAttack(s.player),1000);
            CompoundTag saved=s.player.saveWithoutId(new CompoundTag());GameProfile profile=s.player.getGameProfile();s.fixture.close();
            try(var restored=new Actor(h,profile)){
                restored.load(saved);
                h.assertTrue(restored.getData(ModAttachments.PAUL_DUEL_PROGRESS).won(),"Native player NBT restores the personal victory attachment");
                h.assertTrue(PaulDuel.forPlayer(restored)==null,"A new player object cannot resume an old live bout");
            }
            h.succeed();
        }));
    }

    @GameTest(template="p01_arena",timeoutTicks=170)
    public static void npcKnockbackAtTheArenaEdgeDoesNotCancelTheBout(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.paul.setNoAi(false);s.paul.setPos(b.arena.centre().add(b.arena.radius()-.6,0,0));s.paul.setOnGround(true);
            s.paul.setDeltaMovement(Vec3.ZERO);s.paul.knockback(1,-1,0);
            h.assertTrue(s.paul.getDeltaMovement().x>0,"Ordinary knockback still takes effect outside the rush");
            after(s,5,()->{h.assertTrue(PaulDuel.bout(s.paul)==b&&b.active()&&b.arena.contains(s.paul.position()),"Normal involuntary drift is contained without ending the practice bout");done(s);});
        });
    }
    @GameTest(template="p01_arena",timeoutTicks=260)
    public static void targetingOpponentRoutesUseToCombatItemOnlyForParticipant(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->{
            s.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModContent.TRAINING_STAFF.get()));
            var first=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(s.player,InteractionHand.MAIN_HAND,s.paul);
            dev.joblessreincarnation.npc.FamilyInteractions.general(first);
            h.assertTrue(!first.isCanceled()&&!SpellCasting.canBegin(s.player),"Targeted item use reaches the real first-phase spell gate");
            phaseTwo(s,b,()->{
                s.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModContent.TRAINING_STAFF.get()));s.player.getData(ModAttachments.KNOWLEDGE).learn(SpellCatalog.WATER_BOLT.id());
                var cast=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(s.player,InteractionHand.MAIN_HAND,s.paul);
                dev.joblessreincarnation.npc.FamilyInteractions.general(cast);
                h.assertTrue(!cast.isCanceled()&&SpellCasting.canBegin(s.player),"Second-phase targeting is not swallowed by ordinary NPC dialogue");
                var peer=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(s.peer,InteractionHand.MAIN_HAND,s.paul);
                dev.joblessreincarnation.npc.FamilyInteractions.general(peer);
                h.assertTrue(peer.isCanceled()&&PaulDuel.opponent(s.paul)==s.player,"Spectator keeps the consumed busy-NPC path and cannot join");done(s);
            });
        });
    }

    @GameTest(template="p01_arena",timeoutTicks=290)
    public static void waterStanceAndAllResetsKeepAFiniteNonzeroAnimationClock(GameTestHelper h){
        Setup s=setup(h);begin(s,(x,b)->phaseTwo(s,b,()->{
            PaulDuel.play(b,PaulDuel.Move.TO_WATER);advance(s.paul,10);PaulDuel.tick(s.paul);advance(s.paul,4);
            var patch=PaulDuelCombat.patch(s.paul);var clock=patch.getAnimator().getPlayerFor(null);
            h.assertTrue(b.move==PaulDuel.Move.PARRY&&Float.isFinite(clock.getElapsedTime()),"Natural entry into water stance has a finite loop clock");
            h.assertTrue(clock.getRealAnimation().get().getTotalTime()>0,"The water stance is not a zero-duration empty animation");
            for(int stance=0;stance<=3;stance++){
                s.paul.setDuelStance(stance);PaulDuelCombat.stopAction(s.paul);advance(s.paul,5);
                var player=patch.getAnimator().getPlayerFor(null);
                h.assertTrue(Float.isFinite(player.getElapsedTime())&&player.getRealAnimation().get().getTotalTime()>0,"Every reset uses a genuine finite idle/guard clip");
            }
            s.paul.setDuelStance(3);PaulDuelCombat.stopAction(s.paul);
            after(s,25,()->{h.assertTrue(Float.isFinite(patch.getAnimator().getPlayerFor(null).getElapsedTime()),"Leaving the held stance also keeps a finite clock");done(s);});
        }));
    }

}
