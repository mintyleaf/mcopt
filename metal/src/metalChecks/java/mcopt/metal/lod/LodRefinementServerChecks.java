package mcopt.metal.lod;

import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.serialization.Dynamic;
import java.net.Proxy;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.*;
import net.minecraft.server.dedicated.*;
import net.minecraft.server.jsonrpc.JsonRpc;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.*;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.*;

/** Real vanilla FULL generation in a disposable world. Never opens a launcher instance or an existing save. */
public final class LodRefinementServerChecks {
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		if("end".equals(System.getenv("MCOPT_LOD_SCOUT_PROBE"))) System.setProperty("mcopt.lod.importSaved","false");
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Path root = Files.createTempDirectory("mcopt-full-refinement-");
		DedicatedServer server = null;
		var camera = Executors.newSingleThreadScheduledExecutor();
		var active = new AtomicReference<LodRefinement>();
		try (var captures = new LodCaptures(root.resolve("lod"),6,true)) {
			Path properties = root.resolve("server.properties");
			Files.writeString(properties,"online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-seed=12345\nview-distance=2\nsimulation-distance=2\npause-when-empty-seconds=-1\nmax-tick-time=-1\n");
			var settings = new DedicatedServerSettings(properties);
			var storage = LevelStorageSource.createDefault(root).validateAndCreateAccess("world");
			var packs = ServerPacksSource.createPackRepository(storage);
			// Use vanilla's own new-world setup, including datapacks, registries, dimensions and seed settings.
			var configMethod = Main.class.getDeclaredMethod("loadOrCreateConfig",DedicatedServerProperties.class,Dynamic.class,boolean.class,PackRepository.class);
			configMethod.setAccessible(true);
			var config = (WorldLoader.InitConfig)configMethod.invoke(null,settings.getProperties(),null,false,packs);
			var dataMethod = Main.class.getDeclaredMethod("createNewWorldData",DedicatedServerSettings.class,WorldLoader.DataLoadContext.class,Registry.class,boolean.class,boolean.class);
			dataMethod.setAccessible(true);
			WorldLoader.WorldDataSupplier<LevelDataAndDimensions.WorldDataAndGenSettings> data = context -> {
				try { return (WorldLoader.DataLoadOutput<LevelDataAndDimensions.WorldDataAndGenSettings>)dataMethod.invoke(null,settings,context,
					context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM),false,false); }
				catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
			};
			WorldStem stem = Util.blockUntilDone(executor -> WorldLoader.load(config,data,WorldStem::new,Util.backgroundExecutor(),executor)).get(60,TimeUnit.SECONDS);
			var notifications = new NotificationManager();
			var services = Services.create(MinecraftServicesDiscoveryService.create(Proxy.NO_PROXY),root.toFile());
			var rpc = JsonRpc.create(settings,notifications);
			var ready = new CompletableFuture<Void>();
			server = MinecraftServer.spin(thread -> {
				var created = new DedicatedServer(thread,storage,packs,stem,Optional.empty(),settings,DataFixers.getDataFixer(),services,rpc,notifications) {
					@Override protected boolean initServer() throws java.io.IOException {
						boolean initialized = super.initServer();
						if (initialized) ready.complete(null);
						return initialized;
					}
				};
				notifications.setServer(created);
				return created;
			});
			ready.get(90,TimeUnit.SECONDS);
			if ("true".equals(System.getenv("MCOPT_LOD_CONCURRENCY_PROBE"))) {
				checkConcurrency(server,captures,active,camera);
				return;
			}
			if (System.getenv("MCOPT_LOD_SCOUT_PROBE") != null) {
				probeScouting(server,captures,active,camera);
				return;
			}
			if ("true".equals(System.getenv("MCOPT_LOD_END_LOAD_PROBE"))) {
				probeEndLoad(server,captures,active,camera);
				return;
			}
			checkDimension(server,net.minecraft.world.level.Level.NETHER,true);
			checkDimension(server,net.minecraft.world.level.Level.END,false);
			checkEndIslets(server);
			String legacyFixture=System.getenv("MCOPT_LOD_LEGACY_FIXTURE");
			if(legacyFixture!=null) {
				var raw=net.minecraft.nbt.NbtIo.read(Path.of(legacyFixture));
				var saved=LodSavedChunks.decode(server.overworld(),raw,raw.getIntOr("xPos",0),raw.getIntOr("zPos",0));
				if(saved==null || saved.chunk().getHighestFilledSectionIndex()<0) throw new AssertionError("Legacy raw chunk rejected or became air: version="+raw.getIntOr("DataVersion",-1)+", status="+raw.getStringOr("Status","missing")+", pos="+raw.getIntOr("xPos",0)+","+raw.getIntOr("zPos",0)+", keys="+raw.keySet());
				System.out.println("PASS: real legacy saved chunk upgraded before section decoding");
			}
			var captured = new CompletableFuture<LodChunks.Snapshot>();
			DedicatedServer running = server;
			server.submit(() -> {
				var level = running.overworld();
				var refinement = new LodRefinement(level,captures,(snapshot,expected) -> {
					try {
						if (!level.getChunkSource().getChunkNow(snapshot.chunkX(),snapshot.chunkZ()).isLightCorrect()) throw new AssertionError("FULL returned unlit chunk");
						if (level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.pack(snapshot.chunkX(),snapshot.chunkZ()))) throw new AssertionError("Background FULL enabled simulation");
						boolean lit = false;
						for (boolean valid:snapshot.lightValid()) lit |= valid;
						if (!lit) throw new AssertionError("FULL snapshot lost vanilla light");
						captures.captureIfUnchanged(snapshot,expected,(summary,stamp)->captured.complete(snapshot));
						active.get().close();
					} catch (Throwable error) { captured.completeExceptionally(error); }
				},() -> false);
				active.set(refinement);
				running.addTickable(() -> LodRefinement.tick(level)); // same server-thread hook as the production mixin
			}).get(5,TimeUnit.SECONDS);
			camera.scheduleAtFixedRate(() -> { var r=active.get();if(r!=null) r.focus(1600,1600,1,0,2); },0,16,TimeUnit.MILLISECONDS);
			LodChunks.Snapshot snapshot = captured.get(90,TimeUnit.SECONDS);
			if (active.get().debugLines().stream().noneMatch(line -> line.startsWith("LOD structures:"))
				|| active.get().debugLines().stream().noneMatch(line -> line.startsWith("LOD populated:")))
				throw new AssertionError("Server did not publish its nonblocking F3 progress snapshot");
			server.submit(() -> {
				var source = running.overworld().getChunkSource();
				if (source.isPositionTicking(net.minecraft.world.level.ChunkPos.pack(snapshot.chunkX(),snapshot.chunkZ()))) throw new AssertionError("Refinement retained simulation");
				try {
					if (active.get().activeFull()!=0 || active.get().activeProbes()!=0) throw new AssertionError("Closed refinement scheduled more generation");
					var ticketField = LodRefinement.class.getDeclaredField("ticket");ticketField.setAccessible(true);
					var storageField = source.getClass().getDeclaredField("ticketStorage");storageField.setAccessible(true);
					var tickets = (net.minecraft.world.level.TicketStorage)storageField.get(source);
					for (var ticket:tickets.getTickets(net.minecraft.world.level.ChunkPos.pack(snapshot.chunkX(),snapshot.chunkZ())))
						if (ticket.getType()==ticketField.get(active.get())) throw new AssertionError("Closed refinement retained loading ticket");
				} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
				running.saveEverything(true,true,true);
			}).get(30,TimeUnit.SECONDS);
			var netherSaved=new LodSavedChunks.Window(server.getLevel(net.minecraft.world.level.Level.NETHER)).snapshot(0,0);
			var endSaved=new LodSavedChunks.Window(server.getLevel(net.minecraft.world.level.Level.END)).snapshot(0,0);
			if(netherSaved==null || endSaved==null) throw new AssertionError("Saved chunk import used the wrong dimension directory");
			int netherCavities=0,endStone=0;
			for(int i=0;i<256;i++) {
				if(netherSaved.crownLo()[i]>netherSaved.height()[i]) netherCavities++;
				if(endSaved.top()[i].is(net.minecraft.world.level.block.Blocks.END_STONE)) endStone++;
			}
			if(netherCavities==0 || endStone==0) throw new AssertionError("Saved Nether/End terrain lost its dimension geometry/materials");
			System.out.println("PASS: same-coordinate saved Nether and End chunks keep separate floors, ceilings and materials");
			// Read the saved chunk on this worker, then ensure imported lighting/geometry matches vanilla's snapshot.
			var saved = new LodSavedChunks.Window(server.overworld()).snapshot(snapshot.chunkX(),snapshot.chunkZ());
			if (saved == null || !java.util.Arrays.equals(snapshot.height(),saved.height()) || !java.util.Arrays.equals(snapshot.water(),saved.water()))
				throw new AssertionError("Saved import differs from freshly generated FULL terrain");
			if (!captures.has(LodForest.chunkKey(snapshot.chunkX(),snapshot.chunkZ()))) throw new AssertionError("FULL snapshot not persisted");
			// A fresh LOD cache must discover and import this saved chunk without another generation request.
			try (var fresh = new LodCaptures(root.resolve("fresh-lod"),6,true)) {
				var imported = new CompletableFuture<LodChunks.Snapshot>();
				server.submit(() -> {
					var refinement = new LodRefinement(running.overworld(),fresh,(candidate,expected) -> {
						fresh.captureIfUnchanged(candidate,expected,(summary,stamp) -> {
							if (candidate.chunkX()==snapshot.chunkX() && candidate.chunkZ()==snapshot.chunkZ()) imported.complete(candidate);
						});
					},() -> false);
					try {
						var cooldown = LodRefinement.class.getDeclaredField("nextStart");cooldown.setAccessible(true);cooldown.setLong(refinement,Long.MAX_VALUE);
					} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
					active.set(refinement);
				}).get(5,TimeUnit.SECONDS);
				var actual = imported.get(30,TimeUnit.SECONDS);
				if (!java.util.Arrays.equals(snapshot.height(),actual.height())) throw new AssertionError("Queued saved import changed terrain");
				server.submit(() -> active.get().close()).get(5,TimeUnit.SECONDS);
			}
			System.out.println("PASS: actual vanilla FULL generation without simulation, populated/lit snapshots, capture persistence and bounded saved-import worker in disposable world");
		} finally {
			camera.shutdownNow();
			var refinement = active.get();if(refinement!=null) refinement.close();
			if (server!=null) server.halt(true);
			try (var paths=Files.walk(root)) { for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
		}
	}

	/** Delayed completion models an async chunk mod without sleeping or blocking any server thread. */
	private static void checkConcurrency(DedicatedServer server,LodCaptures captures,AtomicReference<LodRefinement> active,
		java.util.concurrent.ScheduledExecutorService camera) throws Exception {
		var release = new CompletableFuture<Void>();
		var done = new CompletableFuture<Void>();
		var held = new java.util.concurrent.atomic.AtomicBoolean();
		var published = new java.util.concurrent.atomic.AtomicInteger();
		var peakFull = new java.util.concurrent.atomic.AtomicInteger();
		var peakProbes = new java.util.concurrent.atomic.AtomicInteger();
		var owned = new java.util.HashSet<Long>();
		var requestsField = LodRefinement.class.getDeclaredField("requests");requestsField.setAccessible(true);
		var futureField = LodRefineGate.class.getDeclaredField("future");futureField.setAccessible(true);
		server.submit(() -> {
			var level = server.overworld();
			var r = new LodRefinement(level,captures,(snapshot,stamp) -> {
				try {
					var chunk = level.getChunkSource().getChunkNow(snapshot.chunkX(),snapshot.chunkZ());
					if(chunk==null || !chunk.isLightCorrect() || level.getChunkSource().isPositionTicking(chunk.getPos().pack()))
						throw new AssertionError("Concurrent FULL lost publication/light or enabled simulation");
					captures.captureIfUnchanged(snapshot,stamp,(summary,expected)->{});
					published.incrementAndGet();
				} catch(Throwable error) {done.completeExceptionally(error);}
			},()->false);
			active.set(r);
			server.addTickable(() -> {
				try {
					LodRefinement.tick(level);
					if(active.get()!=r) return;
					peakFull.accumulateAndGet(r.activeFull(),Math::max);peakProbes.accumulateAndGet(r.activeProbes(),Math::max);
					var requests=(java.util.Map<?,?>)requestsField.get(r);
					for(var entry:requests.entrySet()) {
						owned.add((Long)entry.getKey());
						var probe=entry.getValue().getClass().getDeclaredField("probe");probe.setAccessible(true);
						if(!probe.getBoolean(entry.getValue()) && held.compareAndSet(false,true)) {
							var gate=entry.getValue().getClass().getDeclaredField("gate");gate.setAccessible(true);
							Object value=gate.get(entry.getValue());
							@SuppressWarnings("unchecked") var original=(CompletableFuture<Object>)futureField.get(value);
							futureField.set(value,original.thenCompose(result->release.thenApply(ignored->result)));
						}
					}
					if(r.activeFull()>LodConfig.WORKLOAD.full || r.activeProbes()>LodConfig.WORKLOAD.probes)
						throw new AssertionError("Concurrent work exceeded preset limits");
					if(peakFull.get()>=2 && peakProbes.get()>=1) release.complete(null);
					if(release.isDone() && published.get()>=3) done.complete(null);
				} catch(Throwable error) {done.completeExceptionally(error);}
			});
		}).get(5,TimeUnit.SECONDS);
		camera.scheduleAtFixedRate(()->active.get().focus(1600,1600,1,0,2),0,16,TimeUnit.MILLISECONDS);
		try { done.get(45,TimeUnit.SECONDS); }
		finally {release.complete(null);server.submit(()->active.get().close()).get(10,TimeUnit.SECONDS);}
		server.submit(() -> {
			try {
				var field=server.overworld().getChunkSource().getClass().getDeclaredField("ticketStorage");field.setAccessible(true);
				var tickets=(net.minecraft.world.level.TicketStorage)field.get(server.overworld().getChunkSource());
				var type=LodRefinement.class.getDeclaredField("ticket");type.setAccessible(true);
				for(long key:owned) for(var ticket:tickets.getTickets(net.minecraft.world.level.ChunkPos.pack((int)(key>>32),(int)key)))
					if(ticket.getType()==type.get(active.get())) throw new AssertionError("Concurrent close retained a loading ticket");
			} catch(ReflectiveOperationException e){throw new AssertionError(e);}
		}).get(10,TimeUnit.SECONDS);
		System.out.println("PASS: real FULL publication/light with delayed async completion: peak "+peakFull+" FULL + "+peakProbes+" probes, "+published+" captures; all loading tickets released on close");
	}

	private static void checkDimension(DedicatedServer server,net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,boolean ceiling) throws Exception {
		var complete=new CompletableFuture<Void>();
		server.submit(() -> {
			var level=server.getLevel(dimension);
			var source=level.getChunkSource();
			var ticket=LodRefinement.newTicket();
			var pos=new net.minecraft.world.level.ChunkPos(0,0);
			source.addTicketAndLoadWithRadius(ticket,pos,0).whenComplete((result,error) -> server.execute(() -> {
				try {
					if(error!=null) throw new AssertionError(error);
					var chunk=source.getChunkNow(0,0);
					if(chunk==null) throw new AssertionError("Dimension FULL did not load: "+dimension);
					var noise=LodNoise.of(level);
					if(noise==null || (noise.cavernY!=LodCaverns.NONE)!=ceiling) throw new AssertionError("Dimension generator rejected or wrong geometry mode");
					var snapshot=LodSavedChunks.liveSnapshot(level,chunk);
					if(ceiling) {
						int cavities=0;
						for(int i=0;i<256;i++) if(snapshot.crownLo()[i]>snapshot.height()[i] && snapshot.height()[i]<128) cavities++;
						if(cavities==0) throw new AssertionError("Nether snapshot retained only bedrock roof");
						var tile=new LodTile(0,0,0,16);
						var geometry=noise.cavernGeometry(tile);
						int estimated=0;
						for(int i=0;i<256;i++) if(geometry.ceilings()[i]>tile.height[i] && tile.height[i]<128) estimated++;
						if(estimated==0) throw new AssertionError("Nether density estimate retained only bedrock roof");
						System.out.println("PASS: actual Nether FULL chunk: "+cavities+" interior columns; density estimates: "+estimated+" floor/ceiling columns");
					} else {
						int solid=0;for(var top:snapshot.top()) if(!top.isAir()) solid++;
						if(solid==0) throw new AssertionError("End island lost during capture");
						checkIslands(noise,chunk,snapshot);
						checkChorus(noise);
						System.out.println("PASS: actual End FULL chunk and island capture use their own dimension geometry");
					}
					complete.complete(null);
				} catch(Throwable failure) { complete.completeExceptionally(failure); }
				finally { source.removeTicketWithRadius(ticket,pos,0); }
			}));
		}).get(5,TimeUnit.SECONDS);
		complete.get(90,TimeUnit.SECONDS);
	}

	private static void checkEndIslets(DedicatedServer server) throws Exception {
		var ready=new CompletableFuture<Void>();
		server.submit(()->{
			try {
				var level=server.getLevel(net.minecraft.world.level.Level.END);var noise=LodNoise.of(level);
				var islets=new LodEndIslets(noise);int[] found=null;
				for(int z=64;z<112 && found==null;z++) for(int x=64;x<112 && found==null;x++) {
					int[] columns=islets.columns(x,z);
					if(columns.length==0) continue;
					for(int i=0;i<columns.length;i+=4) {
						var tile=new LodTile(0,columns[i],columns[i+1],1);noise.islandGeometry(tile);
						if(tile.height[0]==LodIslands.EMPTY) {found=java.util.Arrays.copyOfRange(columns,i,i+4);break;}
					}
				}
				if(found==null) throw new AssertionError("No small End-island feature found");
				int[] chosen=found;
				// Finite geometry and tile-boundary agreement at every resolution, with bounded coarse allocation.
				var allocated=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
				allocated.setThreadAllocatedMemoryEnabled(true);long maxBytes=0,maxNanos=0;
				for(int l=0;l<=4;l++) {
					int step=1<<l,tx=Math.floorDiv(chosen[0],step),tz=Math.floorDiv(chosen[1],step);
					var tile=new LodTile(l,tx*step,tz*step,1);
					noise.islandGeometry(tile);long before=allocated.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();islets.apply(tile);
					maxBytes=Math.max(maxBytes,allocated.getThreadAllocatedBytes(Thread.currentThread().threadId())-before);
					maxNanos=Math.max(maxNanos,System.nanoTime()-start);
					if(tile.height[0]<chosen[3] || tile.islandBottom[0]>chosen[2] || tile.islandBottom[0]==LodIslands.EMPTY)
						throw new AssertionError("Small End island lost at level "+l);
				}
				var large=new LodTile(4,Math.floorDiv(chosen[0],1024),Math.floorDiv(chosen[1],1024));noise.islandGeometry(large);
				long before=allocated.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();islets.apply(large);
				long bytes=allocated.getThreadAllocatedBytes(Thread.currentThread().threadId())-before,elapsed=System.nanoTime()-start;
				if(bytes>32L*1024*1024) throw new AssertionError("Islet replay allocates too much per coarse tile: "+bytes);
				System.out.println(String.format(java.util.Locale.ROOT,"mcopt islet check: coarse tile %.2f MiB, %.1fms; sample max %.1f KiB %.1fms",bytes/1048576.0,elapsed/1e6,maxBytes/1024.0,maxNanos/1e6));
				var pos=new net.minecraft.world.level.ChunkPos(chosen[0]>>4,chosen[1]>>4);var ticket=LodRefinement.newTicket();
				level.getChunkSource().addTicketAndLoadWithRadius(ticket,pos,0).whenComplete((value,error)->server.execute(()->{
					try {
						if(error!=null) throw new AssertionError(error);
						var chunk=level.getChunkSource().getChunkNow(pos.x(),pos.z());
						for(int y=chosen[2];y<chosen[3];y++) if(!chunk.getBlockState(new net.minecraft.core.BlockPos(chosen[0],y,chosen[1])).is(net.minecraft.world.level.block.Blocks.END_STONE))
							throw new AssertionError("Predicted small island differs from actual FULL generation at y="+y);
						System.out.println("PASS: exact small End-island feature matches vanilla FULL at "+chosen[0]+","+chosen[1]+", all five LOD levels retain finite geometry");
						ready.complete(null);
					} catch(Throwable e) {ready.completeExceptionally(e);}
					finally {level.getChunkSource().removeTicketWithRadius(ticket,pos,0);}
				}));
			} catch(Throwable e) {ready.completeExceptionally(e);}
		}).get(10,TimeUnit.SECONDS);
		ready.get(90,TimeUnit.SECONDS);
	}

	private static void checkIslands(LodNoise noise,net.minecraft.world.level.chunk.LevelChunk chunk,LodChunks.Snapshot snapshot) {
		if(noise.islands==null) throw new AssertionError("End's native 8x4 grid was rejected");
		var allocated=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
		allocated.setThreadAllocatedMemoryEnabled(true);
		long maxBytes=0;
		for(int level=0;level<=4;level++) {
			var tile=new LodTile(level,0,0);
			long before=allocated.getThreadAllocatedBytes(Thread.currentThread().threadId());
			noise.islandGeometry(tile);
			long bytes=allocated.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
			maxBytes=Math.max(maxBytes,bytes);
			if(bytes>32L*1024*1024) throw new AssertionError("Strided End density expanded into a dense block volume: level "+level+", bytes "+bytes);
			int[] g=new int[LodTile.CELLS],c=new int[LodTile.CELLS],cr=new int[LodTile.CELLS],runs=new int[LodTile.CELLS];
			LodField.pack(tile,g,c,cr,runs,null);
			for(int i=0;i<tile.cells();i++) {
				if((cr[i]&LodClip.CROWN_BOUNDED)==0 || (g[i]&LodClip.GEOM_WET)!=0)
					throw new AssertionError("Island lost its finite underside or void became water");
			}
			if(level==0) for(int z=0;z<16;z+=8) for(int x=0;x<16;x+=8) {
				int actualTop=chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z)+1;
				int bottom=actualTop-1;
				var pos=new net.minecraft.core.BlockPos.MutableBlockPos();
				while(bottom>chunk.getMinY() && !chunk.getBlockState(pos.set(x,bottom-1,z)).isAir()) bottom--;
				int i=z*64+x;
				if(Math.abs(actualTop-tile.height[i])>1 || Math.abs(bottom-tile.islandBottom[i])>1)
					throw new AssertionError("LOD island differs from vanilla FULL at "+x+","+z+": actual "+bottom+".."+actualTop+", LOD "+tile.islandBottom[i]+".."+tile.height[i]);
			}
		}
		var voidTile=new LodTile(0,512,0,16);noise.islandGeometry(voidTile);
		for(short top:voidTile.height) if(top!=LodIslands.EMPTY) throw new AssertionError("End's void ring became terrain");
		var summary=LodChunks.summarize(snapshot);
		if(summary.islandBottom()==null || summary.islandBottom()[0]>=summary.height()[0]) throw new AssertionError("Live capture lost island underside");
		System.out.println(String.format(java.util.Locale.ROOT,"PASS: End native grid, FULL island top/underside agreement, empty void and five LOD levels; maximum sampler allocation %.2f MiB/tile",maxBytes/1048576.0));
	}

	private static void checkChorus(LodNoise noise) {
		var forest=new LodForest(noise);int found=0;
		// These checks run without Fabric mixins. Supply End's default end-stone material alongside actual density/biomes.
		for(int z=96;z<108;z++) for(int x=96;x<108;x++) forest.offer(chorusTile(noise,x,z));
		for(int z=100;z<104 && found==0;z++) for(int x=100;x<104 && found==0;x++) {
			var tile=chorusTile(noise,x,z);forest.plant(tile);noise.dressTrees(tile);
			int[] g=new int[tile.cells()],c=g.clone(),cr=g.clone(),runs=g.clone();LodField.pack(tile,g,c,cr,runs,null);
			for(int i=0;i<tile.cells();i++) if(tile.canopyState[i]!=null && LodChunks.chorus(tile.canopyState[i])) {
				found++;
				if((g[i]&LodClip.GEOM_CROWN)==0 || (cr[i]&LodClip.CROWN_BOUNDED)==0 || (cr[i]&4095)-512!=tile.ground[i]
					|| (runs[i]&4095)-512!=tile.islandBottom[i]) throw new AssertionError("Predicted chorus replaces its finite island with a plant column");
			}
		}
		if(found==0 || forest.failures.get()!=0) throw new AssertionError("Vanilla chorus feature was skipped or failed: plants "+found+", failures "+forest.failures.get());
		System.out.println("PASS: actual vanilla chorus feature predicts "+found+" plant columns, retaining purple vegetation, finite islands and branch air gaps");
	}
	private static LodTile chorusTile(LodNoise noise,int cx,int cz) {
		var tile=new LodTile(0,cx*16,cz*16,16);noise.islandGeometry(tile);
		for(int i=0;i<tile.cells();i++) {
			tile.ground[i]=tile.height[i];tile.state[i]=net.minecraft.world.level.block.Blocks.END_STONE.defaultBlockState();
			tile.biome[i]=noise.resolver().getNoiseBiome((tile.x0+(i&15))>>2,tile.height[i]>>2,(tile.z0+(i>>4))>>2);
		}
		return tile;
	}

	/** Optional profiling harness: no players, GPU or existing save; isolates the FULL producer's dependency footprint. */
	private static void probeEndLoad(DedicatedServer server,LodCaptures captures,AtomicReference<LodRefinement> active,ScheduledExecutorService camera) throws Exception {
		var count=new java.util.concurrent.atomic.AtomicInteger();
		var level=server.getLevel(net.minecraft.world.level.Level.END);
		server.submit(() -> {
			var refinement=new LodRefinement(level,captures,(snapshot,expected) -> {
				captures.captureIfUnchanged(snapshot,expected,(summary,stamp)->{});
				count.incrementAndGet();
			},()->false);
			active.set(refinement);
			server.addTickable(()->LodRefinement.tick(level));
		}).get(5,TimeUnit.SECONDS);
		camera.scheduleAtFixedRate(()->active.get().focus(1600,1600,1,0,2),0,16,TimeUnit.MILLISECONDS);
		long started=System.nanoTime();
		try {
			for(int i=0;i<8;i++) {
				Thread.sleep(2000);
				String sample=server.submit(()->{
					long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
					long maxTick=java.util.Arrays.stream(server.getTickTimesNanos()).max().orElse(0);
					long gc=java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();
					return String.format("END LOAD: %.1fs, captures %d, loaded %d, heap %.0f MiB, max tick %.1fms, GC total %dms",(System.nanoTime()-started)/1e9,count.get(),level.getChunkSource().getLoadedChunksCount(),heap/1048576.0,maxTick/1e6,gc);
				}).get(20,TimeUnit.SECONDS);
				System.out.println(sample);
			}
		} finally { server.submit(()->active.get().close()).get(20,TimeUnit.SECONDS); }
	}

	private static boolean cityBlock(net.minecraft.world.level.block.state.BlockState state) {
		if(state==null) return false;
		String name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return name.startsWith("purpur_") || name.startsWith("end_stone_brick");
	}

	private static void probeScouting(DedicatedServer server,LodCaptures captures,AtomicReference<LodRefinement> active,ScheduledExecutorService camera) throws Exception {
		boolean end="end".equals(System.getenv("MCOPT_LOD_SCOUT_PROBE"));
		var far=new CompletableFuture<LodChunks.Snapshot>();var level=end?server.getLevel(net.minecraft.world.level.Level.END):server.overworld();
		int distance=end?32:128;
		server.submit(()->{
			var r=new LodRefinement(level,captures,(snapshot,expected)->{
				captures.captureIfUnchanged(snapshot,expected,(summary,stamp)->{
					if(Math.abs(snapshot.chunkX()-100)>distance || Math.abs(snapshot.chunkZ()-100)>distance) {
						boolean city=false;
						for(var state:snapshot.top()) city|=cityBlock(state);
						for(var state:snapshot.crown()) city|=cityBlock(state);
						if(!end || city && Math.hypot(snapshot.chunkX()-100,snapshot.chunkZ()-100)<=128) {
							if(end) {
								var capture=LodCaptures.pack(summary);
								for(int l=0;l<=4;l++) {
									boolean retained=false;
									for(int t:capture.atLevel(l)[LodCaptures.MATERIAL])
										retained|=cityBlock(LodPalette.stateOf(t&1023)) || cityBlock(LodPalette.stateOf(t>>>10&1023));
									if(!retained) {far.completeExceptionally(new AssertionError("End city lost at LOD level "+l));return;}
								}
							}
							far.complete(snapshot);
						}
					}
				});
			},()->false);
			if(end) {
				// Model a region-header index containing partial chunks. It cannot prove FULL geometry exists.
				try {
					var field=LodRefinement.class.getDeclaredField("saved");field.setAccessible(true);
					var hints=java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
					for(int z=-128;z<=128;z++) for(int x=-128;x<=128;x++)
						if(x*x+z*z<=128*128) hints.add(LodForest.chunkKey(100+x,100+z));
					field.set(r,hints);
				} catch(ReflectiveOperationException e) {throw new AssertionError(e);}
			}
			active.set(r);server.addTickable(()->LodRefinement.tick(level));
		}).get(5,TimeUnit.SECONDS);
		camera.scheduleAtFixedRate(()->active.get().focus(1600,1600,1,0,distance),0,16,TimeUnit.MILLISECONDS);
		try {
			var snapshot=far.get(end?45:150,TimeUnit.SECONDS);
			server.submit(()->{
				try {
					var field=LodRefinement.class.getDeclaredField("poi");field.setAccessible(true);var poi=(LodPoiPlan)field.get(active.get());
					if(poi.structures<1 || poi.validated<1) throw new AssertionError("Distant FULL capture was not a confirmed structure footprint");
					if(level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.pack(snapshot.chunkX(),snapshot.chunkZ()))) throw new AssertionError("Scout enabled simulation");
					System.out.println("PASS: production scout validated "+poi.validated+" partial candidates and "+poi.structures+" structure starts, generated/lit/persisted FULL footprint beyond "+distance+" chunks in "+level.dimension().identifier()+" at "+snapshot.chunkX()+","+snapshot.chunkZ()+", without simulation");
				} catch(ReflectiveOperationException e) {throw new AssertionError(e);}
			}).get(10,TimeUnit.SECONDS);
		} finally {server.submit(()->active.get().close()).get(10,TimeUnit.SECONDS);}
	}
}
