package dev.joblessreincarnation.worldgen.buena;

import dev.joblessreincarnation.registry.ModAttachments;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Piece/resident receipts are saved in the same chunk as the generated blocks and entity NBT. */
public final class BuenaChunkMarks {
    public static boolean done(ChunkAccess chunk,UUID village,String piece){
        if(!chunk.hasData(ModAttachments.BUENA_CHUNK))return false;
        CompoundTag t=chunk.getData(ModAttachments.BUENA_CHUNK);
        return t.getInt("format")==1&&t.hasUUID("village")&&t.getUUID("village").equals(village)&&t.getCompound("pieces").getBoolean(piece);
    }
    public static boolean writable(ChunkAccess chunk,UUID village){
        if(!chunk.hasData(ModAttachments.BUENA_CHUNK))return true;
        CompoundTag t=chunk.getData(ModAttachments.BUENA_CHUNK);
        return t.getInt("format")==1&&t.hasUUID("village")&&t.getUUID("village").equals(village);
    }
    public static void mark(ChunkAccess chunk,UUID village,String piece){
        if(!writable(chunk,village))throw new IllegalStateException("Refusing to replace village chunk identity");
        CompoundTag t=chunk.hasData(ModAttachments.BUENA_CHUNK)?chunk.getData(ModAttachments.BUENA_CHUNK).copy():new CompoundTag();
        t.putInt("format",1);t.putUUID("village",village);
        CompoundTag pieces=t.getCompound("pieces");pieces.putBoolean(piece,true);t.put("pieces",pieces);
        chunk.setData(ModAttachments.BUENA_CHUNK,t);chunk.setUnsaved(true);
    }
    public static UUID completedVillage(ChunkAccess chunk){
        if(!chunk.hasData(ModAttachments.BUENA_CHUNK))return null;
        CompoundTag t=chunk.getData(ModAttachments.BUENA_CHUNK);
        return t.getInt("format")==1&&t.hasUUID("village")&&t.getCompound("pieces").getBoolean("complete")?t.getUUID("village"):null;
    }
    private BuenaChunkMarks(){}
}
