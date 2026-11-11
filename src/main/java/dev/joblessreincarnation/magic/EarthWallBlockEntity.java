package dev.joblessreincarnation.magic;

import dev.joblessreincarnation.registry.ModContent;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Server-only provenance, saved with each extant cell. Legacy/command-created cells remain unowned. */
public final class EarthWallBlockEntity extends BlockEntity {
    @Nullable private WallIdentity identity;

    public EarthWallBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.EARTH_WALL_ENTITY.get(), pos, state);
    }

    public Optional<WallIdentity> identity() { return Optional.ofNullable(identity); }

    public void bind(WallIdentity value) {
        if (!value.contains(getBlockPos())) throw new IllegalArgumentException("Wall identity does not contain this cell");
        if (identity != null && !identity.equals(value)) throw new IllegalStateException("Wall cell already belongs to another construction");
        identity = value;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (identity != null) tag.put("WallIdentity", identity.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        identity = tag.contains("WallIdentity", Tag.TAG_COMPOUND)
                ? WallIdentity.load(tag.getCompound("WallIdentity")).filter(value -> value.contains(getBlockPos())).orElse(null)
                : null;
    }
}
