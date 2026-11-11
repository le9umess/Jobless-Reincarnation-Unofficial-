package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.JoblessReincarnation;
import dev.joblessreincarnation.registry.ModAttachments;
import dev.joblessreincarnation.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared geometric preview and authoritative fixed-size soil construction. */
public final class EarthWallSpell {
    public static final ResourceLocation SPELL = ResourceLocation.fromNamespaceAndPath(JoblessReincarnation.MOD_ID, "earth_wall");

    public record Plan(List<BlockPos> positions, boolean valid, Component reason) {
        public Plan { positions = positions.stream().map(BlockPos::immutable).toList(); }
    }

    public static boolean isLearned(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) && entity.getData(ModAttachments.KNOWLEDGE).knows(SPELL);
    }

    public static int practiceCount(LivingEntity entity) {
        return entity.hasData(ModAttachments.KNOWLEDGE) ? entity.getData(ModAttachments.KNOWLEDGE).practiceCount(SPELL) : 0;
    }

    public static int chantTicks(LivingEntity entity) {
        return SpellTraining.chantTicks(MagicConfig.EARTH_WALL_CHANT_TICKS.getAsInt(), practiceCount(entity));
    }

    static Plan result(List<BlockPos> positions, String reason) {
        return new Plan(positions, reason.equals("ready"), Component.translatable("message.jobless_reincarnation.earth_wall." + reason));
    }

    /** Client estimates terrain only. Protection handlers and payment are checked again on release. */
    public static Plan plan(Player player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(MagicConfig.EARTH_WALL_RANGE.getAsInt()));
        // Level.clip can request chunks. Walk the same cells without reading states before clipping.
        BlockPos unloaded = BlockGetter.traverseBlocks(eye, end, level,
                (world, pos) -> world.hasChunkAt(pos) ? null : pos.immutable(), world -> null);
        if (unloaded != null) return result(List.of(), "unloaded");
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK) return result(List.of(), "range");
        if (hit.getDirection() != Direction.UP) return result(List.of(), "top_surface");
        BlockPos bottom = hit.getBlockPos().above();
        Direction across = player.getDirection().getClockWise();
        List<BlockPos> positions = new ArrayList<>(6);
        // Center first: the placement event's primary snapshot has the actual aimed support below it.
        for (int offset : new int[]{0, -1, 1}) {
            BlockPos pos = bottom.relative(across, offset);
            positions.add(pos);
            positions.add(pos.above());
        }
        return validate(player, positions, false);
    }

    /** With formed=true, checks post-event integrity without treating our own newly placed cells as obstacles. */
    static Plan validate(Player player, List<BlockPos> positions, boolean formed) {
        Level level = player.level();
        if (positions.size() != 6 || positions.stream().distinct().count() != 6) return result(positions, "blocked");
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(new AABB(pos))) return result(positions, "bounds");
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())) return result(positions, "unloaded");
            BlockState state = level.getBlockState(pos);
            if (!level.getFluidState(pos).isEmpty()) return result(positions, "fluid");
            if (formed ? !state.is(ModContent.EARTH_WALL.get()) : !state.isAir()) return result(positions, "blocked");
            if (i % 2 == 0) {
                BlockPos support = pos.below();
                if (level.isOutsideBuildHeight(support) || !level.getWorldBorder().isWithinBounds(new AABB(support))
                        || !level.getFluidState(support).isEmpty()
                        || !level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) return result(positions, "support");
            }
            AABB cell = new AABB(pos);
            if (player.getBoundingBox().intersects(cell)
                    || !level.getEntities((net.minecraft.world.entity.Entity) null, cell, entity -> !entity.isRemoved()).isEmpty()) return result(positions, "entity");
        }
        return result(positions, "ready");
    }

    static boolean allowed(ServerPlayer player, List<BlockPos> positions) {
        // Magic never uses adventure CanPlaceOn components to bypass the explicit no-building rule.
        if (!player.getAbilities().mayBuild || player.isSpectator()) return false;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            if (!player.serverLevel().mayInteract(player, pos)
                    || !player.mayUseItemAt(pos, Direction.UP, player.getMainHandItem())
                    || (i % 2 == 0 && !player.serverLevel().mayInteract(player, pos.below()))) return false;
        }
        return true;
    }

    public static boolean canBeginCast(ServerPlayer player) {
        return canBeginCast(player, SpellPayment.NORMAL);
    }
    private static boolean canBeginCast(ServerPlayer player, SpellPayment mode) {
        if (!player.isAlive() || player.isSpectator() || !player.getMainHandItem().is(ModContent.TRAINING_STAFF.get())) return false;
        if (!isLearned(player)) {
            player.displayClientMessage(Component.translatable("message.jobless_reincarnation.earth_wall.learn_first"), true);
            return false;
        }
        ManaData mana = ManaGrowth.prepare(player);
        int cost = MagicConfig.EARTH_WALL_COST.getAsInt();
        if (!mana.canPay(cost, player.getAbilities().instabuild, mode)) {
            if (mana.cooldown() == 0) player.displayClientMessage(Component.translatable("message.jobless_reincarnation.not_enough_mana", cost), true);
            return false;
        }
        return true;
    }

    /** Chanting is handled by the staff; this entry point always rechecks resources and construction on the server. */
    public static boolean cast(ServerPlayer player) {
        return cast(player, SpellPayment.NORMAL);
    }
    static boolean cast(ServerPlayer player, SpellPayment mode) {
        if (!canBeginCast(player, mode)) return false;
        Plan plan = plan(player);
        if (!plan.valid()) return fail(player, plan.reason());
        if (!allowed(player, plan.positions())) return fail(player, result(plan.positions(), "protected").reason());
        int cost = MagicConfig.EARTH_WALL_COST.getAsInt();
        int cooldown = MagicConfig.EARTH_WALL_COOLDOWN.getAsInt();
        boolean creative = player.getAbilities().instabuild;
        ManaData.Payment[] payment = new ManaData.Payment[1];
        boolean placed = EarthWallPlacement.place(player, plan, () -> {
            // Event handlers may have changed the held item, knowledge, mode or mana attachment.
            if (!canBeginCast(player, mode) || player.getAbilities().instabuild != creative) return false;
            payment[0] = ManaGrowth.prepare(player).trySpendWithReceipt(cost, MagicConfig.RECOVERY_DELAY_TICKS.getAsInt(), cooldown, creative, mode);
            return payment[0] != null;
        }, () -> ManaGrowth.onSpellPaid(player, payment[0]));
        if (!placed) return fail(player, result(plan.positions(), "protected").reason());
        player.syncData(ModAttachments.MANA);
        player.getCooldowns().addCooldown(ModContent.TRAINING_STAFF.get(), cooldown);
        if (!creative && mode == SpellPayment.NORMAL) {
            MagicKnowledge knowledge = player.getData(ModAttachments.KNOWLEDGE);
            SpellTraining.Stage previous = SpellTraining.stage(knowledge.practiceCount(SPELL));
            if (knowledge.practice(SPELL)) {
                player.syncData(ModAttachments.KNOWLEDGE);
                SpellTraining.Stage stage = SpellTraining.stage(knowledge.practiceCount(SPELL));
                if (stage != previous) player.displayClientMessage(Component.translatable("message.jobless_reincarnation.training_improved_named", SpellCatalog.EARTH_WALL.title(), stage.title()), true);
            }
        }
        BlockPos center = plan.positions().getFirst();
        player.serverLevel().playSound(null, center, SoundEvents.ROOTED_DIRT_PLACE, SoundSource.PLAYERS, 0.8F, 0.65F);
        for (int i = 0; i < plan.positions().size(); i += 2) {
            BlockPos pos = plan.positions().get(i);
            player.serverLevel().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModContent.EARTH_WALL.get().defaultBlockState()),
                    pos.getX() + 0.5, pos.getY() + 0.15, pos.getZ() + 0.5, 6, 0.4, 0.1, 0.4, 0.02);
        }
        return true;
    }

    private static boolean fail(ServerPlayer player, Component reason) {
        player.displayClientMessage(reason, true);
        return false;
    }

    private EarthWallSpell() {}
}
