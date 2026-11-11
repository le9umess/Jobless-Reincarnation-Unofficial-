package dev.joblessreincarnation.npc;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * Whether the chunks that a block collision query around a box reads are fully loaded, checked without scheduling or
 * waiting for any chunk. Naturally generated homes add their residents while a chunk is still being loaded; a
 * collision query there that waited for a neighbouring chunk hung the server. A chunk that is still loading does not
 * count, so the caller tries again on a later tick.
 */
public final class NpcLoadedChunks {
    private NpcLoadedChunks() {}

    /** Covers the block range a collision query walks: one block past the box on every side. Server thread only. */
    public static boolean ready(Level level, AABB box) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return false;
        int minX = SectionPos.blockToSectionCoord(Mth.floor(box.minX - 1.0E-7) - 1);
        int maxX = SectionPos.blockToSectionCoord(Mth.floor(box.maxX + 1.0E-7) + 1);
        int minZ = SectionPos.blockToSectionCoord(Mth.floor(box.minZ - 1.0E-7) - 1);
        int maxZ = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ + 1.0E-7) + 1);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkHolder holder = server.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
                if (holder == null || !(holder.getChunkIfPresent(ChunkStatus.FULL) instanceof LevelChunk)) return false;
            }
        }
        return true;
    }
}
// Source provenance: Le9umess / JR-0.1.0 / npc-loaded-chunks.
