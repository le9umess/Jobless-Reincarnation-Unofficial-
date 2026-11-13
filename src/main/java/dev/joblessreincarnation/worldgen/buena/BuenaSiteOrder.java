package dev.joblessreincarnation.worldgen.buena;

import java.util.*;
import net.minecraft.world.level.ChunkPos;

/**
 * The survey order of one Buena site search. Pure and deterministic: it never reads a clock and never depends on which
 * survey finished first. Frozen for 0.1.1:
 * <ol>
 * <li>The list starts as the whole 0.1.0 grid in the 0.1.0 order (on the vanilla generator, the climate-hint group
 *     first), and every grid origin is in the seen set before the first site is surveyed.</li>
 * <li>Sites are consumed strictly in list order. When a grid site is rejected for a late planning reason
 *     ({@link #deep}), its neighbours are inserted directly after it: origins on an 8-block grid within 32 blocks of
 *     the parent's origin on each axis (a square, at most 80, the parent excluded), ordered by distance from the
 *     parent, then x, then z. A neighbour whose origin is already seen (a grid origin, consumed or still to come, or
 *     an earlier neighbour) is skipped, so a grid origin is never tried early and no origin is tried twice.</li>
 * <li>At most {@link #CAP} neighbours are inserted in one search, counting only the unique origins actually inserted.
 *     A parent that meets the cap gets the first ones in the order above; after that refinement stops and the grid
 *     goes on unchanged. Inserted neighbours are never refined themselves.</li>
 * <li>A neighbour's parent is the consumption number (1-based) of the parent site, not its grid index.</li>
 * </ol>
 */
public final class BuenaSiteOrder {
    /** A village origin (its -8,-8 corner is origin+MIN) and where it came from. group: 1 climate hint first, 0 later, -1 none. */
    public record Site(int x,int z,String tag,int parent,int group){
        public long key(){return ChunkPos.asLong(x,z);}
        /** The chunk of the village centre, origin+88, which is also the plan's anchor. */
        public ChunkPos chunk(){return new ChunkPos(Math.floorDiv(x+88,16),Math.floorDiv(z+88,16));}
    }
    /** Step and reach of one parent's neighbours, in blocks; neighbours inserted per search. */
    public static final int STEP=8,RADIUS=32,CAP=96;
    /** The fixed neighbour offsets, nearest the parent first, then by x and z; 80 of them. */
    static final List<int[]> NEIGHBOURS=neighbours();
    /** Planning failures late enough that a small move may pass. The trace keeps the first reason only. */
    private static final Set<String> DEEP=Set.of("exact_pad_relief","construction_over_water","construction_relief","home_practice_relief",
            "inconsistent_pad_heights","excess_earthwork","river_profile","river_pad_conflict","river_bank_earthwork","no_dry_spawn_approach");
    private final List<Site> order=new ArrayList<>();
    private final Set<Long> seen=new HashSet<>();
    private final boolean refine;
    private int refined;

    /** The shipped order. */
    public BuenaSiteOrder(){this(true);}
    /** Tests: false keeps the plain 0.1.0 grid order with no neighbours. */
    public BuenaSiteOrder(boolean refine){this.refine=refine;}
    /** Appends a site unless the same origin is already listed; says whether it was added. */
    public boolean add(Site site){if(!seen.add(site.key()))return false;order.add(site);return true;}
    public int size(){return order.size();}
    public Site get(int index){return order.get(index);}
    /** Neighbours inserted so far. */
    public int refined(){return refined;}

    /**
     * The site at index was rejected for reason. If the rules above refine it, its neighbours are inserted at index+1
     * and returned; otherwise nothing changes. check runs once per neighbour (cancellation).
     */
    public List<Site> rejected(int index,String reason,int parentNumber,Runnable check){
        Site site=order.get(index);
        if(!refine||!"grid".equals(site.tag())||refined>=CAP||!deep(reason))return List.of();
        List<Site> extra=new ArrayList<>();
        for(int[] o:NEIGHBOURS){
            if(refined+extra.size()>=CAP)break;
            check.run();
            var near=new Site(site.x()+o[0],site.z()+o[1],"s1",parentNumber,-1);
            if(seen.add(near.key()))extra.add(near);
        }
        order.addAll(index+1,extra);refined+=extra.size();
        return extra;
    }
    public static boolean deep(String reason){return DEEP.contains(reason)||reason.startsWith("pad_fit_");}
    private static List<int[]> neighbours(){
        List<int[]> out=new ArrayList<>();int n=RADIUS/STEP;
        for(int j=-n;j<=n;j++)for(int i=-n;i<=n;i++)if(i!=0||j!=0)out.add(new int[]{i*STEP,j*STEP});
        out.sort(Comparator.<int[]>comparingInt(o->o[0]*o[0]+o[1]*o[1]).thenComparingInt(o->o[0]).thenComparingInt(o->o[1]));
        return List.copyOf(out);
    }
    /** A 0.1.0 grid candidate as an absolute origin: the reference chunk plus the offset, chunk middle minus 88. */
    public static Site grid(ChunkPos reference,ChunkPos offset,int group){
        var chunk=new ChunkPos(reference.x+offset.x,reference.z+offset.z);
        return new Site(chunk.getMiddleBlockX()-88,chunk.getMiddleBlockZ()-88,"grid",-1,group);
    }
}
