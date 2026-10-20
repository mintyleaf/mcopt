package mcopt.metal.lod;

import com.mojang.serialization.Codec;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

/** Offline CPU/latency sweep of the production capture path on detached saved chunks; never opens a live world. */
public final class LodSnapshotSweep {
	private static volatile long sink;
	private static boolean clientCopy;
	private static net.minecraft.world.level.chunk.PalettedContainerFactory copyFactory;
	// Conservative copy load: all light sections have dense data and all four adjacent chunks have populated sections.
	private static final net.minecraft.world.level.chunk.DataLayer COPY_LIGHT = new net.minecraft.world.level.chunk.DataLayer(new byte[2048]);
	private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	private static final com.sun.management.ThreadMXBean ALLOC = (com.sun.management.ThreadMXBean) THREADS;
	private static final com.sun.management.OperatingSystemMXBean OS = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

	public static void main(String[] args) throws Exception {
		if (args.length != 3) throw new IllegalArgumentException("Expected fixture directory, output CSV, profile label");
		clientCopy = args[2].equals("client_copy");
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		var bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class); bind.setAccessible(true);
		for (Block b : BuiltInRegistries.BLOCK) {
			String name = BuiltInRegistries.BLOCK.getKey(b).getPath();
			if (name.endsWith("_leaves")) bind.invoke(b.builtInRegistryHolder(), List.of(BlockTags.LEAVES));
			if (name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_stem") || name.endsWith("_hyphae"))
				bind.invoke(b.builtInRegistryHolder(), List.of(BlockTags.LOGS));
		}
		var biome = Holder.direct(new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8F).downfall(0.4F)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
			.generationSettings(BiomeGenerationSettings.EMPTY).mobSpawnSettings(MobSpawnSettings.EMPTY).build());
		IdMapper<Holder<Biome>> ids = new IdMapper<>(); ids.add(biome);
		var blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
		var factory = new PalettedContainerFactory(blockStrategy, Blocks.AIR.defaultBlockState(),
			PalettedContainer.codecRW(BlockState.CODEC, blockStrategy, Blocks.AIR.defaultBlockState()), Strategy.createForBiomes(ids), biome,
			PalettedContainer.codecRO(Codec.STRING.xmap(s -> biome, b -> "minecraft:plains"), Strategy.createForBiomes(ids), biome));
		var height = LevelHeightAccessor.create(-64, 384);
		copyFactory = factory;
		var chunks = new ArrayList<LodSavedChunks.Saved>();
		try (var paths = Files.list(Path.of(args[0]))) {
			for (Path path : paths.filter(p -> p.toString().endsWith(".nbt")).sorted().toList()) {
				var raw = SerializableChunkData.parse(height, factory, net.minecraft.nbt.NbtIo.read(path));
				if (raw == null) throw new IllegalStateException("Could not decode " + path.getFileName());
				var saved = LodSavedChunks.detached(height, raw, true);
				if (!raw.heightmaps().containsKey(Heightmap.Types.WORLD_SURFACE))
					Heightmap.primeHeightmaps(saved.chunk(), EnumSet.of(Heightmap.Types.WORLD_SURFACE));
				chunks.add(saved);
			}
		}
		if (chunks.isEmpty()) throw new IllegalStateException("No saved chunk fixtures");
		if (ALLOC.isThreadAllocatedMemorySupported() && !ALLOC.isThreadAllocatedMemoryEnabled()) ALLOC.setThreadAllocatedMemoryEnabled(true);
		long warm=System.nanoTime();int warmed=0;
		do {run(chunks.get(warmed++ % chunks.size()), null, null, null);} while(warmed<512 || System.nanoTime()-warm<3_000_000_000L);
		Path output = Path.of(args[1]); Files.createDirectories(output.toAbsolutePath().getParent());
		try (var out = new PrintWriter(Files.newBufferedWriter(output))) {
			out.println("profile,sweep_step,load_threads,stage,samples,mean_wall_ms,p50_wall_ms,p95_wall_ms,p99_wall_ms,max_wall_ms,mean_thread_cpu_ms,mean_alloc_bytes,process_cpu_percent,available_processors");
			int step=0;
			for (int load : new int[]{0, 2, 4, 6, 0}) {
				var stop = new AtomicBoolean(); var workers = new ArrayList<Thread>();
				try {
					for (int i=0; i<load; i++) {
						Thread t = new Thread(() -> { double v=0.123; while (!stop.get()) { for(int n=0;n<4096;n++)v=Math.fma(v,0.999999,0.000001); } sink ^= Double.doubleToRawLongBits(v); }, "snapshot-sweep-load-"+i);
						t.setDaemon(true); t.start(); workers.add(t);
					}
					Thread.sleep(100);
					Samples snapshot = new Samples(), summarize = new Samples(), mips = new Samples();
					long start=System.nanoTime(), cpu=OS.getProcessCpuTime(); int n=0;
					do { run(chunks.get(n++ % chunks.size()), snapshot, summarize, mips); } while (n<96 || System.nanoTime()-start<1_000_000_000L);
					double usage=100.0*(OS.getProcessCpuTime()-cpu)/(System.nanoTime()-start)/Runtime.getRuntime().availableProcessors();
					snapshot.write(out,args[2],step,load,clientCopy?"client_copy":"snapshot",usage); summarize.write(out,args[2],step,load,clientCopy?"worker_scan_pack":"summarize_pack",usage); mips.write(out,args[2],step,load,"coarse_mips",usage);
				} finally { stop.set(true); for(Thread t:workers)t.join(2000); }
				step++;
			}
		}
		System.out.println("CPU sweep: "+chunks.size()+" detached chunks, 0/2/4/6/0 competing threads; "+output);
	}

	private static void run(LodSavedChunks.Saved saved, Samples snapshots, Samples summaries, Samples mips) {
		long start=System.nanoTime(),cpu=THREADS.getCurrentThreadCpuTime(),allocated=ALLOC.getCurrentThreadAllocatedBytes();
		LodClientCapture.Frozen frozen = clientCopy ? LodClientCapture.freeze(saved.chunk(),
			new net.minecraft.world.level.chunk.ChunkAccess[]{saved.chunk(),saved.chunk(),saved.chunk(),saved.chunk()},copyFactory,-5,26,
			true,true,(layer,pos)->COPY_LIGHT,LodCaverns.NONE,false) : null;
		var snapshot=clientCopy ? null : LodChunks.snapshot(saved.chunk(),saved.lighting());
		if(snapshots!=null)snapshots.add(start,cpu,allocated);
		start=System.nanoTime();cpu=THREADS.getCurrentThreadCpuTime();allocated=ALLOC.getCurrentThreadAllocatedBytes();
		if(clientCopy) snapshot=frozen.snapshot();
		var capture=LodCaptures.pack(LodChunks.summarize(snapshot));
		if(summaries!=null)summaries.add(start,cpu,allocated);
		start=System.nanoTime();cpu=THREADS.getCurrentThreadCpuTime();allocated=ALLOC.getCurrentThreadAllocatedBytes();
		for(int l=1;l<=4;l++)sink+=capture.atLevel(l)[0][0];
		if(mips!=null)mips.add(start,cpu,allocated);
	}

	private static final class Samples {
		final ArrayList<Long> wall=new ArrayList<>(); long cpu,allocated;
		void add(long start,long beforeCpu,long beforeAllocation) { wall.add(System.nanoTime()-start);cpu+=THREADS.getCurrentThreadCpuTime()-beforeCpu;allocated+=Math.max(0,ALLOC.getCurrentThreadAllocatedBytes()-beforeAllocation); }
		void write(PrintWriter out,String profile,int step,int load,String stage,double usage) {
			wall.sort(Long::compare);int n=wall.size();double mean=wall.stream().mapToLong(Long::longValue).average().orElse(0)/1e6;
			out.printf(Locale.ROOT,"%s,%d,%d,%s,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.1f,%.2f,%d%n",profile,step,load,stage,n,mean,pct(.5),pct(.95),pct(.99),wall.get(n-1)/1e6,cpu/1e6/n,(double)allocated/n,usage,Runtime.getRuntime().availableProcessors());
		}
		double pct(double p) {return wall.get(Math.max(0,(int)Math.ceil(wall.size()*p)-1))/1e6;}
	}
}
