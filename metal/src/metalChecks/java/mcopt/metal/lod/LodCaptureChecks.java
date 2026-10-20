package mcopt.metal.lod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import mcopt.metal.MetalBridge;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import sun.misc.Unsafe;

/** Real capture codec and generated-result install, including eviction and capture/generation races. */
public final class LodCaptureChecks {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		Path root = Files.createTempDirectory("mcopt-lod-captures-");
		try {
			// A visited clearing must replace every plane of a procedural canopy, including unlit captures.
			LodCaptures store = new LodCaptures(root, 10, true, 1);
			LodCaptures.Capture clearing = LodCaptures.pack(summary(0, 0, 64));
			store.put(clearing);
			store.put(LodCaptures.pack(summary(-4, 4, 85)));
			LodChunks.Summary unlit = summary(4, -4, 70);
			for (int i=0;i<256;i++) unlit.tex()[i] &= ~LodClip.TEX_LIGHT;
			store.put(LodCaptures.pack(unlit));
			store.close(); // drains pending writes, not dependent on the periodic tile save or any resident tile
			checkOverlay(store, 0, 0, 64);
			checkOverlay(store, -4, 4, 85);
			checkOverlay(store, 4, -4, 70, false);
			// The previous release's fine format remains readable, without regenerating its visited chunks.
			Path legacy=root.resolve("real-chunks/0.0.lodc");byte[] payload;
			try(var input=new java.util.zip.InflaterInputStream(Files.newInputStream(legacy))) {payload=input.readAllBytes();}
			java.nio.ByteBuffer.wrap(payload).putInt(4,1);
			try(var output=new java.util.zip.DeflaterOutputStream(Files.newOutputStream(legacy))) {output.write(payload);}
			if (store.revision(LodTile.key(8, 0, 0)) == 0) throw new AssertionError("Coarse capture revision missing");
			// A fresh store has no resident tiles or in-memory summaries; disk corrections must still win at every scale.
			try (LodCaptures reopened = new LodCaptures(root, 10, true, 1)) {
				checkOverlay(reopened, 0, 0, 64);
				checkOverlay(reopened, -4, 4, 85);
				checkOverlay(reopened, 4, -4, 70, false);
				checkRace(reopened);
				for (int i = 0; i < 300; i++) reopened.put(LodCaptures.pack(summary(0, 0, 90 + i % 20)));
			}
			try (LodCaptures reopened = new LodCaptures(root, 10, true, 1)) {
				checkOverlay(reopened, 0, 0, 109); // latest edit wins despite coalescing and shutdown
				checkOverlay(reopened, -4, 4, 85);
				var memory = LodCaptures.class.getDeclaredField("memory"); memory.setAccessible(true);
				if (((java.util.Map<?, ?>) memory.get(reopened)).size() > 1) throw new AssertionError("Capture memory is not bounded");
			}
			// Captures from another world/dimension namespace must never leak into this one.
			try (LodCaptures other = new LodCaptures(root.resolve("other-world"), 10, true)) {
				int[][] p = tile();
				other.overlay(LodTile.key(0,0,0),p[0],p[1],p[2],p[3],p[4],p[5]);
				if ((p[0][0] & LodClip.GEOM_CROWN) == 0) throw new AssertionError("Captured terrain leaked across worlds");
			}
			checkIncrementalSaves(root.resolve("incremental"));
			checkIslandCaptures(root.resolve("islands"));
			checkStructureCaptures(root.resolve("structures"));
			checkLegacyChorus(root.resolve("legacy-chorus"));
			checkLegacyOverhang(root.resolve("legacy-overhang"));
			System.out.println("PASS: phantom canopies replaced by real ground; structures/material/light/plant data survive memory eviction, disk restart, coarse levels and latest edits; late generation cannot overwrite a visit; capture payloads remain bounded");
		} finally {
			try (var files = Files.walk(root)) {
				for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
			}
		}
	}
	private static void checkLegacyChorus(Path root) throws Exception {
		int[][] p=LodCaptures.pack(summary(0,0,80)).planes();
		p[LodCaptures.MATERIAL][0]=LodPalette.word(LodPalette.id(Blocks.CHORUS_FLOWER.defaultBlockState()),LodPalette.id(Blocks.CHORUS_PLANT.defaultBlockState()),0);
		try(var store=new LodCaptures(root,5,true)) {store.put(new LodCaptures.Capture(0,0,p));}
		Path file=root.resolve("real-chunks/0.0.lodc");byte[] data;
		try(var in=new java.util.zip.InflaterInputStream(Files.newInputStream(file))) {data=in.readAllBytes();}
		java.nio.ByteBuffer.wrap(data).putInt(4,2);
		try(var out=new java.util.zip.DeflaterOutputStream(Files.newOutputStream(file))) {out.write(data);}
		try(var store=new LodCaptures(root,5,true)) {
			int[][] destination=tile();store.overlay(LodTile.key(0,0,0),destination[0],destination[1],destination[2],destination[3],destination[4],destination[5]);
			if(store.has(LodForest.chunkKey(0,0)) || (destination[0][0]&4095)-512!=140)
				throw new AssertionError("Old chorus overwrites repaired estimates or blocks saved-chunk importing");
		}
		System.out.println("PASS: legacy chorus capture is scheduled for saved-chunk refresh without deleting world data");
	}
	private static void checkLegacyOverhang(Path root) throws Exception {
		checkLegacyOverhang(root.resolve("tagged"),true);
		checkLegacyOverhang(root.resolve("deep"),false);
	}
	private static void checkLegacyOverhang(Path root,boolean tagged) throws Exception {
		int[][] p=LodCaptures.pack(summary(0,0,80)).planes();
		if(tagged) p[LodCaptures.MATERIAL][0]|=LodClip.TEX_OVERHANG;
		else p[LodCaptures.GEOM][0]|=LodClip.depthBits(80);
		try(var store=new LodCaptures(root,5,true)) {store.put(new LodCaptures.Capture(0,0,p));}
		Path file=root.resolve("real-chunks/0.0.lodc");byte[] data;
		try(var in=new java.util.zip.InflaterInputStream(Files.newInputStream(file))) {data=in.readAllBytes();}
		java.nio.ByteBuffer.wrap(data).putInt(4,4);
		try(var out=new java.util.zip.DeflaterOutputStream(Files.newOutputStream(file))) {out.write(data);}
		try(var store=new LodCaptures(root,5,true)) {
			int[][] destination=tile();store.overlay(LodTile.key(0,0,0),destination[0],destination[1],destination[2],destination[3],destination[4],destination[5]);
			if(store.has(LodForest.chunkKey(0,0)) || (destination[0][0]&4095)-512!=140)
				throw new AssertionError("Old filled overhang blocks its vertical refresh");
		}
		System.out.println("PASS: legacy overhangs are scheduled for vertical refresh without deleting world data");
	}

	private static void checkStructureCaptures(Path root) throws Exception {
		for(int packed:new int[]{(3<<30)|(20<<10)|(40<<20),1|(1<<6)|(1<<12)|(1<<20)})
			checkStructureCapture(root.resolve(Integer.toUnsignedString(packed)),packed);
		System.out.println("PASS: exact and sampled vertical intervals with coarse block light survive disk restart at all five levels");
	}
	private static void checkStructureCapture(Path root,int packed) throws Exception {
		int[][] source=new int[LodCaptures.PLANES][256];
		Arrays.fill(source[LodCaptures.GEOM],LodClip.crownGeomWord(121,41,false));
		Arrays.fill(source[LodCaptures.CROWN],LodClip.crownWord(64,0xAABBCC)|LodClip.CROWN_SEGMENTED);
		Arrays.fill(source[LodCaptures.RUNS],packed);Arrays.fill(source[LodCaptures.MATERIAL],LodClip.TEX_LIGHT);
		Arrays.fill(source[LodCaptures.LIGHT],14<<8);
		try(var store=new LodCaptures(root,5,true,1)) {store.put(new LodCaptures.Capture(0,0,source));}
		try(var store=new LodCaptures(root,5,true,1)) {
			for(int level=0;level<5;level++) {
				int[][] p=tile();store.overlay(LodTile.key(level,0,0),p[0],p[1],p[2],p[3],p[4],p[5]);
				if((p[2][0]&LodClip.CROWN_SEGMENTED)==0 || p[4][0]!=packed || (p[0][0]&4095)-512!=121
					|| level>0 && (p[2][0]>>>24&15)!=14) throw new AssertionError("Restart lost segmented floors or distant block light at level "+level);
			}
		}
	}

	private static LodChunks.Summary summary(int cx, int cz, int height) {
		short[] y = new short[256], water = new short[256], lo = new short[256], hi = new short[256];
		Arrays.fill(y, (short) height); Arrays.fill(hi, (short) height);
		Arrays.fill(water, LodTile.DRY); Arrays.fill(lo, Short.MIN_VALUE);
		int[] top = new int[256], side = new int[256], below = new int[256], tex = new int[256], light = new int[256];
		int[] pa = new int[256], pb = new int[256];
		Arrays.fill(top, 0xCCAA44); Arrays.fill(side, 0x997744); Arrays.fill(below, 0x555555);
		int material = LodPalette.word(LodPalette.id(Blocks.OAK_PLANKS.defaultBlockState()), LodPalette.id(Blocks.STONE.defaultBlockState()), 0);
		Arrays.fill(tex, material | LodClip.TEX_LIGHT); Arrays.fill(light, 0xF0E1D2C3);
		pa[0] = LodClip.plantWordA(LodPalette.id(Blocks.SHORT_GRASS.defaultBlockState()), 0, 1); pb[0] = 0x12345678;
		return new LodChunks.Summary(cx, cz, y, water, top, side, below, new boolean[256], lo, hi, new int[256], tex,
			new int[256], pa, pb, new boolean[256], new byte[256], light, null, null);
	}

	private static int[][] tile() {
		int n = LodTile.CELLS;
		int[][] p = {new int[n],new int[n],new int[n],new int[2*n],new int[n],new int[2*n]};
		Arrays.fill(p[0], LodClip.crownGeomWord(140, 20, false));
		Arrays.fill(p[1], 0x76543210); Arrays.fill(p[2], 0x31415926);
		Arrays.fill(p[3], 0x12345678); Arrays.fill(p[4], 12345); Arrays.fill(p[5], 34567);
		return p;
	}

	private static void checkOverlay(LodCaptures store, int cx, int cz, int height) {
		checkOverlay(store,cx,cz,height,true);
	}

	private static void checkOverlay(LodCaptures store, int cx, int cz, int height, boolean lit) {
		for (int l = 0; l < 10; l++) {
			int span = 64 << l, step = 1 << l;
			int tx = Math.floorDiv(cx * 16, span), tz = Math.floorDiv(cz * 16, span);
			int[][] p = tile();
			store.overlay(LodTile.key(l,tx,tz),p[0],p[1],p[2],l==0?p[3]:null,p[4],l==0?p[5]:null);
			for (int i = 0; i < LodTile.CELLS; i++) {
				int x = tx * span + (i & 63) * step, z = tz * span + (i >> 6) * step;
				boolean captured = x >> 4 == cx && z >> 4 == cz;
				if (!captured) continue; // another fixture may live in the same coarse tile
				if ((p[0][i] & 4095) - 512 != height || (p[0][i] & LodClip.GEOM_CROWN) != 0 || p[4][i] != 0)
					throw new AssertionError("Procedural canopy replaced visited ground/build at level " + l);
				int k = (z & 15) * 16 + (x & 15);
				if (l == 0 && (p[3][LodTile.CELLS+i] != 0xF0E1D2C3 || ((p[3][i] & LodClip.TEX_LIGHT) != 0) != lit
					|| (k==0 && p[5][LodTile.CELLS+i] != 0x12345678))) throw new AssertionError("Capture codec lost light/material/plant plane");
				if (l==0 && (LodPalette.stateOf(p[3][i]&1023)!=Blocks.OAK_PLANKS.defaultBlockState()
					|| LodPalette.stateOf(p[3][i]>>>10&1023)!=Blocks.STONE.defaultBlockState()
					|| (k==0 && LodPalette.stateOf(p[5][i]&1023)!=Blocks.SHORT_GRASS.defaultBlockState())))
					throw new AssertionError("Capture palette no longer names the original block states");
			}
		}
	}

	private static void checkRace(LodCaptures captures) throws Exception {
		Field uf = Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
		LodField field = (LodField) ((Unsafe) uf.get(null)).allocateInstance(LodField.class);
		long ctx = MetalBridge.createHeadlessContext();
		LodClip clip = new LodClip(ctx,256,300);
		try {
			clip.recenter(0,0);
			var jobs = new PriorityBlockingQueue<>();
			set(field,"captures",captures); set(field,"clip",clip); set(field,"jobs",new LodJobQueue<>(j -> LodJobQueue.CACHE)); set(field,"maintenance",jobs);
			set(field,"results",new ConcurrentLinkedQueue<>()); set(field,"pending",new ConcurrentHashMap<>());
			set(field,"seq",new AtomicLong()); set(field,"waiting",new HashMap<>());
			set(field,"chunkBuilt",new HashSet<>()); set(field,"missingKeys",new HashSet<>());
			Class<?>[] signature = {long.class,int[].class,int[].class,int[].class,int[].class,int[].class,int[].class};
			Method finish = LodField.class.getDeclaredMethod("finishGenerated",signature); finish.setAccessible(true);
			int[][] p = tile(); long key = LodTile.key(0,0,0);
			finish.invoke(field,key,p[0],p[1],p[2],p[3],p[4],p[5]); // old generated result is now queued for install
			long version = captures.revision(key);
			captures.put(LodCaptures.pack(summary(0,0,85)));
			long end = System.nanoTime()+5_000_000_000L;
			while (captures.revision(key)==version && System.nanoTime()<end) Thread.sleep(1);
			if (captures.revision(key)==version) throw new AssertionError("Capture writer did not complete");
			field.integrate();
			if (clip.resident(0,0,0) || jobs.isEmpty()) throw new AssertionError("Stale generated tile installed over newer visit");
			Object job = jobs.poll(); Method task = job.getClass().getDeclaredMethod("task"); task.setAccessible(true);
			((Runnable) task.invoke(job)).run(); field.integrate();
			if (!clip.resident(0,0,0) || (clip.cellWords(0,0)[0]&4095)-512 != 85)
				throw new AssertionError("Retried tile lost the real building");
			// The important flight case: the visit occurred before its coarse ring tile existed.
			p = tile(); finish.invoke(field,LodTile.key(1,0,0),p[0],p[1],p[2],null,p[4],null); field.integrate();
			clip.read(1,0,0,p[0],p[1],p[2],null,p[4],null);
			if ((p[0][0]&4095)-512 != 85 || (p[0][0]&LodClip.GEOM_CROWN)!=0)
				throw new AssertionError("Village disappeared as a previously absent coarse tile loaded");
			clip.recenter(4096,4096); clip.recenter(0,0);
			p = tile(); finish.invoke(field,LodTile.key(0,0,0),p[0],p[1],p[2],p[3],p[4],p[5]); field.integrate();
			if ((clip.cellWords(0,0)[0]&4095)-512 != 85) throw new AssertionError("Clipmap eviction restored a phantom tree");
		} finally { clip.free(); }
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = LodField.class.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
	}

	private static void checkIncrementalSaves(Path cache) throws Exception {
		Field uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);
		var field=(LodField)((Unsafe)uf.get(null)).allocateInstance(LodField.class);
		var clip=new LodClip(MetalBridge.createHeadlessContext(),256,300);
		var jobs=new PriorityBlockingQueue<>();
		var dirty=new java.util.LinkedHashSet<Long>();
		try {
			clip.recenter(0,0);
			set(field,"clip",clip);set(field,"cache",cache);set(field,"jobs",new LodJobQueue<>(j -> LodJobQueue.CACHE));set(field,"maintenance",jobs);set(field,"dirtyTiles",dirty);
			set(field,"seq",new AtomicLong());set(field,"savingTiles",new java.util.concurrent.atomic.AtomicInteger());
			int[] g=new int[LodTile.CELLS],c=new int[LodTile.CELLS];
			Arrays.fill(g,LodClip.geomWord(64,false));Arrays.fill(c,0x123456);
			for(int x=0;x<3;x++) {
				int tx=x%2,tz=x/2;
				if(!clip.put(1,tx,tz,g,c,null,null,null,null)) throw new AssertionError("Dirty fixture outside clipmap");
				dirty.add(LodTile.key(1,tx,tz));
			}
			field.frame=3000;field.saveDirty(false);
			if(jobs.size()!=1 || dirty.size()!=2) throw new AssertionError("Periodic save copied a whole dirty batch in one frame");
			field.saveDirty(false);
			if(jobs.size()!=1 || dirty.size()!=2) throw new AssertionError("Repeated frame bypassed wall-clock pacing");
			set(field,"nextDirtySave",0L);field.saveDirty(false);
			set(field,"nextDirtySave",0L);field.saveDirty(false);
			if(jobs.size()!=2 || dirty.size()!=1) throw new AssertionError("Slow disk allows unbounded tile snapshots");
			Object job=jobs.poll();Method task=job.getClass().getDeclaredMethod("task");task.setAccessible(true);
			((Runnable)task.invoke(job)).run();
			field.saveDirty(false);
			if(jobs.size()!=2 || !dirty.isEmpty()) throw new AssertionError("Tile saving did not resume after worker progress");
			while((job=jobs.poll())!=null) ((Runnable)task.invoke(job)).run();
			// Closing must still flush the latest dirty geometry without requiring another timed frame.
			Arrays.fill(g,LodClip.geomWord(81,false));clip.put(1,0,0,g,c,null,null,null,null);
			dirty.add(LodTile.key(1,0,0));field.saveDirty(true);
			Method load=LodField.class.getDeclaredMethod("load",long.class,int[].class,int[].class,int[].class,int[].class,int[].class,int[].class);
			load.setAccessible(true);
			for(int x=0;x<3;x++) {
				int[] actualG=new int[LodTile.CELLS],actualC=new int[LodTile.CELLS];
				if(!(boolean)load.invoke(field,LodTile.key(1,x%2,x/2),actualG,actualC,null,null,null,null)
					|| (actualG[0]&4095)-512!=(x==0?81:64) || !Arrays.equals(c,actualC))
					throw new AssertionError("Incremental/closing save lost a tile or its latest edit");
			}
			System.out.println("PASS: incremental tile copies, bounded slow-disk backlog, resumed saving and final dirty flush preserve real clipmap data");
		} finally { clip.free(); }
	}

	private static void checkIslandCaptures(Path root) throws Exception {
		var base=summary(0,0,64);short[] bottom=new short[256];Arrays.fill(bottom,(short)32);
		var s=new LodChunks.Summary(0,0,base.height(),base.water(),base.top(),base.side(),base.below(),base.fringe(),base.crownLo(),
			base.crownHi(),base.groundColor(),base.tex(),base.runs(),base.plantA(),base.plantB(),base.clear(),base.depth(),base.light(),bottom,null);
		s.crownLo()[1]=96;s.crownHi()[1]=100; // roof over an island
		s.height()[2]=s.crownHi()[2]=bottom[2]=LodIslands.EMPTY; // known void
		s.height()[3]=bottom[3]=LodIslands.EMPTY;s.crownLo()[3]=32;s.crownHi()[3]=122; // thick isolated island
		try(var store=new LodCaptures(root,5,true)) { store.put(LodCaptures.pack(s)); }
		try(var reopened=new LodCaptures(root,5,true)) {
			for(int level=0;level<5;level++) {
				int[][] p=tile();reopened.overlay(LodTile.key(level,0,0),p[0],p[1],p[2],p[3],p[4],p[5]);
				int expectedTop=level==0?64:level==1?100:122,expectedLower=level==1?64:32;
				if((p[2][0]&LodClip.CROWN_BOUNDED)==0 || (p[2][0]&4095)-512!=expectedLower || (p[0][0]&4095)-512!=expectedTop)
					throw new AssertionError("Persistent/coarse capture turned a floating island into a column");
				if(level==0 && ((p[0][2]&4095)-512!=LodIslands.EMPTY || p[4][1]!=((32+512)|((96+512)<<12))
					|| p[4][3]!=((32+512)<<12))) throw new AssertionError("Void, roof gap or thick floating interval lost on disk restart");
			}
		}
		Field uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);
		var field=(LodField)((Unsafe)uf.get(null)).allocateInstance(LodField.class);
		var clip=new LodClip(MetalBridge.createHeadlessContext(),256,300,true);
		try {
			clip.recenter(0,0);set(field,"clip",clip);set(field,"dirtyTiles",new HashSet<>());
			for(int level=0;level<clip.levels;level++) clip.put(level,0,0,new int[LodTile.CELLS],new int[LodTile.CELLS],new int[LodTile.CELLS],null,new int[LodTile.CELLS],null);
			Method apply=LodField.class.getDeclaredMethod("applyChunk",LodChunks.Summary.class,int.class,int.class);apply.setAccessible(true);
			apply.invoke(field,s,0,clip.levels);
			long initial=clip.version;
			for(int repeat=0;repeat<1000;repeat++) apply.invoke(field,s,0,clip.levels);
			if(clip.version!=initial) throw new AssertionError("Repeated identical End imports still refresh tiles and trigger mesh work");
			for(int level=0;level<clip.levels;level++) {
				int[] g=new int[LodTile.CELLS],c=new int[LodTile.CELLS],cr=new int[LodTile.CELLS],runs=new int[LodTile.CELLS];
				clip.read(level,0,0,g,c,cr,null,runs,null);
				if((cr[0]&LodClip.CROWN_BOUNDED)==0 || (cr[0]&4095)-512!=(level==1?64:32)) throw new AssertionError("Live reload lost bounded island geometry");
				if(level==0 && ((g[2]&4095)-512!=LodIslands.EMPTY || runs[3]!=((32+512)<<12)))
					throw new AssertionError("Live reload filled known void or the underside of a thick island");
			}
		} finally { clip.free(); }
		System.out.println("PASS: finite End islands, known void and roofs survive capture persistence, all coarse levels and live chunk reloads");
	}
}
