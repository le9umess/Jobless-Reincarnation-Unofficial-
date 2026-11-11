package dev.joblessreincarnation.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** Permanent shaped soil; provenance is removed together with each mined, exploded or replaced cell. */
public final class EarthWallBlock extends Block implements EntityBlock {
    public EarthWallBlock() {
        super(Properties.of().mapColor(MapColor.DIRT).strength(1.0F, 3.0F)
                .sound(SoundType.GRAVEL).pushReaction(PushReaction.BLOCK).noLootTable());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new EarthWallBlockEntity(pos, state); }
}
