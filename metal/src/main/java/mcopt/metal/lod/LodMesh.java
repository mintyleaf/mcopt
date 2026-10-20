package mcopt.metal.lod;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The far terrain as meshes (-Dmcopt.lod.render=mesh): every resident clipmap tile meshed into quads (lodmesh.c, on mesh
 * workers, from the clipmap's own words), kept in one arena buffer the GPU reads, with a table per level and window slot
 * saying where each tile's mesh is. Every frame columns.metal's lod_mesh_cull picks the blocks and quads to draw and the
 * level pass draws them (mclod.m: mcl_mesh_cull, mcl_mesh_draw).
 *
 * A tile is meshed again when its words change (generated, loaded, real chunks) and when a neighbor arrives (its border
 * walls toward it become exact). Replaced meshes are freed RING frames later: frames in flight still read them.
 */
final class LodMesh implements LodClip.Listener {
	/** -Dmcopt.lod.meshBlock=8|16: cells per block side (the GPU's selection and culling granularity). */
	static final int BLOCK = Integer.getInteger("mcopt.lod.meshBlock", 16) == 8 ? 8 : 16;
	static final int BPT = LodClip.TILE / BLOCK, HDR_WORDS = BPT * BPT * 32;
	/** Bytes of columns.metal's MeshFrame. */
	static final int FRAME_BYTES = 432;
	/** -Dmcopt.lod.meshFences=true: the cull's outputs ordered by fences instead of hazard tracking (see mclod.m). */
	static final boolean FENCES = Boolean.getBoolean("mcopt.lod.meshFences") && !LodPk.ENABLED;
	/** -Dmcopt.lod.horizonCull=false: no occlusion cull (what nearer terrain hides). */
	static final boolean HORIZON = Boolean.parseBoolean(System.getProperty("mcopt.lod.horizonCull", "true"));
	/** -Dmcopt.lod.realOcc=true: the real terrain (level 0's blocks the hand-off masks) raises the horizon cull's horizon too. */
	static final boolean REAL_OCC = Boolean.getBoolean("mcopt.lod.realOcc");
	/** -Dmcopt.lod.realOccRule=height: a control, the earlier rule (the camera at or over a run's bottom), wrong under an arch. */
	static final boolean REAL_OCC_HEIGHT = "height".equals(System.getProperty("mcopt.lod.realOccRule"));
	/**
	 * -Dmcopt.lod.hzMark=true (a debug mode, screenshots only): what the horizon would cull is drawn anyway, pure red (hidden
	 * with its block) or green (hidden alone). Any red or green pixel left in the picture is far terrain wrongly culled.
	 */
	static final boolean HZ_MARK = Boolean.getBoolean("mcopt.lod.hzMark");
	private static final int RING = 4;
	/**
	 * -Dmcopt.lod.dissolve=MS: a tile's first mesh in its slot dissolves in over MS ms, cell by cell, from the coarser level that
	 * stood in for it (columns.metal seamDissolve, compiled in only with this flag). 0: off.
	 */
	static final int DISSOLVE_MS = Integer.getInteger("mcopt.lod.dissolve", 0);
	private static final long CLOCK0 = System.nanoTime();

	/** The dissolve's clock: ms since start, 24 bits (CompFrame camFrac.w, the table's install stamps). */
	static int clockMs() {
		return (int) ((System.nanoTime() - CLOCK0) / 1_000_000L) & 0xFFFFFF;
	}
	/** The instance buffer: the culls' own instances, then (with the lists) their sectors' stand-ins from LodPk.STAND_BASE. */
	static final int MAX_INSTANCES = LodPk.ENABLED ? 1 << 21 : 1 << 19;
	private static final int MAX_PLANT_INSTANCES = 1 << 16, Q = 32;
	private static final int SCRATCH_QUADS = 1 << 17;
	/** Surviving quads a frame can draw (16-byte records). */
	private static final int MAX_SURVIVORS = 1 << 21;
	private static final long MAX_ARENA_WORDS = 1L << 29;

	private final long ctx;
	private final LodClip clip;
	private final int tps;
	// the arena (words), its free ranges (offset -> length) and ranges waiting for frames in flight
	private long arenaBuf, arenaAddr, arenaWords;
	private final TreeMap<Long, Long> free = new TreeMap<>();
	private final ArrayDeque<long[]> releaseLater = new ArrayDeque<>();
	private final ArrayDeque<long[]> buffersLater = new ArrayDeque<>();
	private long usedWords;
	private LodArenaGrowth growth;
	private long growthRetryAt;
	private static final long COPY_BYTES = Math.max(1024, Long.getLong("mcopt.lod.meshCopyBytes", 4L << 20));
	private static final long COPY_NANOS = 1_000_000L;
	// per level, per slot: the installed mesh (offset, words; -1: none), the tile it is, the neighbors it was built with, its sequence
	private final long[][] off, len, key;
	private final int[][] builtNeighbors;
	private final long[][] seq;
	/** Per level, per slot: the clipmap slot's word version the installed mesh was requested at (LodClip.slotVer). */
	private final long[][] builtVer;
	// the tile table: per level, per slot {mesh offset + 1 (0: none), tx, tz, 0}, copied each frame into a ring buffer
	private final long tableBytes;
	private final long tableHost;
	private final long[] tableBufs = new long[RING];
	final long argsBuf, instBuf, plantBuf, survBuf, horizonBuf, listBuf;
	private static final int MAX_LISTED = 1 << 16;
	final long frame = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
	// meshing
	private final LodMeshRequests jobs;
	private final LodMeshResults results = new LodMeshResults(
		Math.max(2, Integer.getInteger("mcopt.lod.meshResultMiB", 32)) * 1048576L,
		Math.max(1, Integer.getInteger("mcopt.lod.meshResultCount", 128)));
	/** PAIRS: the latest staged snapshot per tile key, waiting for a mesh job (render thread puts, workers read). */
	private final ConcurrentHashMap<Long, LodClip.Snapshot> snaps = new ConcurrentHashMap<>();
	/** PAIRS: per level, per slot, the staged version last published. */
	private final long[][] publishedVer;
	private static final int PUBLISH_BUDGET = Math.max(1, Math.min(LodPublish.CAP, Integer.getInteger("mcopt.lod.publishBudget", 32)));
	/** publish=gpu: neighbors' remeshes after a publish wait until the GPU has written its words ({frame due, level, tx, tz}). */
	private final ArrayDeque<long[]> requestLater = new ArrayDeque<>();
	/** PAIRS: the scratch plane's log size (a tile and its 1-cell border fit, addressed like the clipmap). */
	private static final int SCRATCH_LOG = 7, SCRATCH = 1 << SCRATCH_LOG;
	final AtomicLong published = new AtomicLong(), seqRetries = new AtomicLong();
	private final AtomicLong nextSeq = new AtomicLong();
	private final Thread[] workers;
	private volatile boolean closed;
	// stats
	final AtomicLong meshed = new AtomicLong(), meshNanos = new AtomicLong(), meshQuads = new AtomicLong();
	int installed;
	private long reusedMeshes,healthAt=System.nanoTime(),healthFrames;
	/** The position-keyed lists (-Dmcopt.lod.pk), told when a tile's mesh changes; else null. */
	@Nullable LodPk pk;

	LodMesh(long ctx, LodClip clip) {
		this.ctx = ctx;
		this.clip = clip;
		this.tps = clip.tilesPerSide;
		this.jobs = new LodMeshRequests(clip.levels, this.tps);
		int slots = this.tps * this.tps;
		this.off = new long[clip.levels][slots];
		this.len = new long[clip.levels][slots];
		this.key = new long[clip.levels][slots];
		this.seq = new long[clip.levels][slots];
		this.builtNeighbors = new int[clip.levels][slots];
		this.builtVer = new long[clip.levels][slots];
		this.publishedVer = new long[clip.levels][slots];
		for (long[] o : this.off) java.util.Arrays.fill(o, -1L);
		for (long[] k : this.key) java.util.Arrays.fill(k, -1L);
		this.tableBytes = (long) clip.levels * slots * 16;
		this.tableHost = MemoryUtil.nmemCalloc(1, this.tableBytes);
		for (int i = 0; i < RING; i++) this.tableBufs[i] = LodNative.buffer(ctx, this.tableBytes);
		this.arenaWords = Math.clamp(Long.getLong("mcopt.lod.meshArenaInitialKiB", 65536L), 16L, MAX_ARENA_WORDS / 256) * 256;
		this.arenaBuf = LodNative.buffer(ctx, this.arenaWords * 4);
		this.arenaAddr = LodNative.contents(this.arenaBuf);
		this.free.put(0L, this.arenaWords);
		// the cull's outputs: hazard-tracked (the whole level pass waits for the cull), or with -Dmcopt.lod.meshFences untracked and
		// ordered by fences (only the far terrain's draw waits: the real terrain's draws before it overlap the cull)
		java.util.function.LongUnaryOperator gpu = FENCES ? n -> LodNative.privateUntrackedBuffer(ctx, n) : n -> LodNative.privateBuffer(ctx, n);
		this.argsBuf = gpu.applyAsLong(512);
		this.instBuf = gpu.applyAsLong((long) MAX_INSTANCES * 32);
		this.plantBuf = gpu.applyAsLong((long) MAX_PLANT_INSTANCES * 32);
		this.survBuf = gpu.applyAsLong((long) MAX_SURVIVORS * 16);
		// (bins x bands, then per bin the far terrain's lowest tangent: columns.metal HZ_FARMIN)
		this.horizonBuf = gpu.applyAsLong(4096L * 129 * 4);
		this.listBuf = gpu.applyAsLong((long) MAX_LISTED * 16);
		int n = Math.max(1, Integer.getInteger("mcopt.lod.meshThreads", 2));
		this.workers = new Thread[n];
		for (int i = 0; i < n; i++) {
			Thread t = new Thread(this::work, "mcopt-lod-mesh-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			t.start();
			this.workers[i] = t;
		}
		clip.listener = this;
	}

	// ---- the clipmap's changes (render thread) ----

	@Override
	public void tilePut(int level, int tx, int tz, boolean refreshed) {
		if (LodClip.PAIRS) LodClip.release(this.snaps.remove(LodTile.key(level, tx, tz)));
		this.request(level, tx, tz);
		// each neighbor's border walls toward this tile: built without it (skirts), or this tile's border cells changed
		int[][] d = {{1, 0, 2}, {-1, 0, 1}, {0, 1, 8}, {0, -1, 4}};
		for (int[] n : d) {
			int nx = tx + n[0], nz = tz + n[1];
			if (!this.clip.resident(level, nx, nz)) continue;
			int ns = this.clip.slot(nx, nz);
			boolean without = this.key[level][ns] != LodTile.key(level, nx, nz) || (this.builtNeighbors[level][ns] & n[2]) == 0;
			if (refreshed || without) this.request(level, nx, nz);
		}
	}

	@Override
	public void tileCleared(int level, int slot) {
		this.jobs.cancel(level, slot);
		this.drop(level, slot);
		if (LodClip.PAIRS) this.snaps.forEach((key, sn) -> {
			if (sn.level == level && this.clip.slot(sn.tx, sn.tz) == slot && this.snaps.remove(key, sn)) sn.release();
		});
	}

	@Override
	public void tileStaged(LodClip.Snapshot snap) {
		LodClip.release(this.snaps.put(LodTile.key(snap.level, snap.tx, snap.tz), snap.retain()));
		this.request(snap.level, snap.tx, snap.tz);
	}

	private void request(int level, int tx, int tz) {
		if (!this.clip.resident(level, tx, tz)) return;
		this.jobs.offer(level, tx, tz, this.nextSeq.incrementAndGet(), this.clip.slotVer[level][this.clip.slot(tx, tz)]);
	}

	// ---- meshing (workers) ----

	private void work() {
		long header = MemoryUtil.nmemAlloc((long) HDR_WORDS * 4);
		long quads = MemoryUtil.nmemAlloc((long) SCRATCH_QUADS * 8);
		// PAIRS: the tile and its 1-cell border copied here (geometry, crowns, runs, plants A and B), meshed from the copy
		long scratch = LodClip.PAIRS ? MemoryUtil.nmemCalloc(5L * SCRATCH * SCRATCH, 4) : 0;
		try {
			while (!this.closed) {
				LodMeshRequests.Request job;
				try {
					job = this.jobs.take();
				} catch (InterruptedException e) {
					return;
				}
				long k = job.key(), s = job.sequence(), ver = job.version();
				int level = LodTile.levelOf(k), tx = LodTile.txOf(k), tz = LodTile.tzOf(k);
				LodClip.Snapshot held = null;
				try {
					if (!this.clip.resident(level, tx, tz)) continue;
					int neighbors = (this.clip.resident(level, tx + 1, tz) ? 1 : 0) | (this.clip.resident(level, tx - 1, tz) ? 2 : 0)
						| (this.clip.resident(level, tx, tz + 1) ? 4 : 0) | (this.clip.resident(level, tx, tz - 1) ? 8 : 0);
					long lw = this.clip.levelWords * 4 * level;
					boolean crowns = level < this.clip.crownLevels;
					long crown = crowns ? this.clip.crown + lw : 0, runs = crowns ? this.clip.crown + this.clip.runsOffset() * 4 + lw : 0;
					boolean plants = level == 0 && this.clip.plants;
					long pa = plants ? this.clip.tex + this.clip.levelWords * 4 : 0, pb = plants ? this.clip.tex + this.clip.levelWords * 8 : 0;
					long t0 = System.nanoTime();
					LodClip.Snapshot snap = null;
					int n;
					if (LodClip.PAIRS) {
						snap = held = LodClip.retain(this.snaps, k);
						long v = this.copyRegion(scratch, level, tx, tz, snap, crowns, plants);
						if (v < 0) {
							// the render thread kept writing: try again later
							this.jobs.retry(job);
							continue;
						}
						ver = v;
						long sp = (long) SCRATCH * SCRATCH * 4;
						n = LodNative.meshTile(scratch, crowns ? scratch + sp : 0, crowns ? scratch + 2 * sp : 0, plants ? scratch + 3 * sp : 0, plants ? scratch + 4 * sp : 0,
							SCRATCH_LOG, level, tx, tz, neighbors, BLOCK, header, quads, SCRATCH_QUADS);
					} else {
						n = LodNative.meshTile(this.clip.geom + lw, crown, runs, pa, pb, this.clip.logN, level, tx, tz, neighbors, BLOCK, header, quads, SCRATCH_QUADS);
					}
					this.meshNanos.addAndGet(System.nanoTime() - t0);
					if (n < 0) {
						System.out.println("mcopt-lod: mesh of tile L" + level + " " + tx + "," + tz + " overflows " + SCRATCH_QUADS + " quads; not drawn");
						continue;
					}
					long words = HDR_WORDS + 2L * n;
					if (!this.jobs.current(job) || !this.clip.resident(level, tx, tz)) continue;
					if (!this.results.reserve(words * 4)) return;
					long block = 0;
					try {
						if (!this.jobs.current(job) || !this.clip.resident(level, tx, tz)) continue;
						block = MemoryUtil.nmemAlloc(words * 4);
						MemoryUtil.memCopy(header, block, (long) HDR_WORDS * 4);
						MemoryUtil.memCopy(quads, block + (long) HDR_WORDS * 4, (long) n * 8);
						this.meshed.incrementAndGet(); this.meshQuads.addAndGet(n);
						var result = this.results.new Result(new long[] {k, s, block, words, neighbors, ver}, snap);
						held = null; block = -1; // result owns native storage, credits and the snapshot lease
						this.results.offer(result);
					} finally {
						if (block >= 0) {
							if (block != 0) MemoryUtil.nmemFree(block);
							this.results.release(words * 4);
						}
					}
				} catch (InterruptedException stopped) {
					return;
				} catch (RuntimeException e) {
					System.out.println("mcopt-lod: mesh job failed: " + e);
				} finally {
					LodClip.release(held);
				}
			}
		} finally {
			MemoryUtil.nmemFree(header);
			MemoryUtil.nmemFree(quads);
			if (scratch != 0) MemoryUtil.nmemFree(scratch);
		}
	}

	/**
	 * PAIRS: copies the tile (from snap where given, else the clipmap) and the 1-cell border of its neighbors (the clipmap) into
	 * the scratch planes, under the slots' sequence locks. Returns the tile slot's sequence (its word version) the copy is of,
	 * or -1 when the render thread kept writing.
	 */
	private long copyRegion(long scratch, int level, int tx, int tz, LodClip.Snapshot snap, boolean crowns, boolean plants) {
		var w = this.clip.writing[level];
		int[] slots = {this.clip.slot(tx, tz), this.clip.slot(tx + 1, tz), this.clip.slot(tx - 1, tz), this.clip.slot(tx, tz + 1), this.clip.slot(tx, tz - 1)};
		long[] before = new long[5];
		long sp = (long) SCRATCH * SCRATCH * 4;
		long lw = this.clip.levelWords * 4 * level;
		long crownBase = this.clip.crown + lw, runsBase = this.clip.crown + this.clip.runsOffset() * 4 + lw;
		long paBase = this.clip.tex + this.clip.levelWords * 4, pbBase = this.clip.tex + this.clip.levelWords * 8;
		int m = this.clip.n - 1, sm = SCRATCH - 1;
		int ax0 = tx * LodClip.TILE, az0 = tz * LodClip.TILE;
		for (int attempt = 0; attempt < 64; attempt++) {
			boolean odd = false;
			for (int i = 0; i < 5; i++) {
				before[i] = w.get(slots[i]);
				odd |= (before[i] & 1) != 0;
			}
			if (odd) {
				Thread.onSpinWait();
				continue;
			}
			LodClip.Snapshot[] pending={snap!=null?snap.retain():this.clip.retainPendingGpu(level,tx,tz),this.clip.retainPendingGpu(level,tx+1,tz),
				this.clip.retainPendingGpu(level,tx-1,tz),this.clip.retainPendingGpu(level,tx,tz+1),this.clip.retainPendingGpu(level,tx,tz-1)};
			try {
				for (int z = -1; z <= LodClip.TILE; z++) {
					for (int x = -1; x <= LodClip.TILE; x++) {
						boolean outX = x < 0 || x >= LodClip.TILE, outZ = z < 0 || z >= LodClip.TILE;
						if (outX && outZ) continue;
						int ax = ax0 + x, az = az0 + z;
						long dst = (((long) (az & sm) << SCRATCH_LOG) | (ax & sm)) * 4;
						int region=x>=LodClip.TILE?1:x<0?2:z>=LodClip.TILE?3:z<0?4:0;
						LodClip.Snapshot source=pending[region];
						if (source != null) {
							int i = (z&63) * LodClip.TILE + (x&63);
							MemoryUtil.memPutInt(scratch + dst, source.g[i]);
							if (crowns) {
								MemoryUtil.memPutInt(scratch + sp + dst, source.cr[i]);
								MemoryUtil.memPutInt(scratch + 2 * sp + dst, source.runs[i]);
							}
							if (plants) {
								MemoryUtil.memPutInt(scratch + 3 * sp + dst, source.pl[i]);
								MemoryUtil.memPutInt(scratch + 4 * sp + dst, source.pl[LodClip.TILE * LodClip.TILE + i]);
							}
							continue;
						}
						long src = (((long) (az & m) << this.clip.logN) | (ax & m)) * 4;
						MemoryUtil.memPutInt(scratch + dst, MemoryUtil.memGetInt(this.clip.geom + lw + src));
						if (crowns) {
							MemoryUtil.memPutInt(scratch + sp + dst, MemoryUtil.memGetInt(crownBase + src));
							MemoryUtil.memPutInt(scratch + 2 * sp + dst, MemoryUtil.memGetInt(runsBase + src));
						}
						if (plants) {
							MemoryUtil.memPutInt(scratch + 3 * sp + dst, MemoryUtil.memGetInt(paBase + src));
							MemoryUtil.memPutInt(scratch + 4 * sp + dst, MemoryUtil.memGetInt(pbBase + src));
						}
					}
				}
				java.lang.invoke.VarHandle.acquireFence();
				boolean same = true;
				for (int i = 0; i < 5; i++) same &= w.get(slots[i]) == before[i];
				if (same) return before[0];
				this.seqRetries.incrementAndGet();
			} finally {
				for (LodClip.Snapshot source : pending) LodClip.release(source);
			}
		}
		return -1;
	}

	// ---- installing meshes (render thread) ----

	/** Once a frame before drawing: finished meshes go into the arena, freed ranges whose frames are done return. */
	void integrate(long frame) {
		this.clip.flushStaged();
		this.advanceGrowth(frame);
		while (!this.releaseLater.isEmpty() && frame - this.releaseLater.peek()[0] >= RING) {
			long[] r = this.releaseLater.poll();
			this.release(r[1], r[2]);
		}
		while (!this.buffersLater.isEmpty() && frame - this.buffersLater.peek()[0] >= RING) LodNative.release(this.buffersLater.poll()[1]);
		int budget = Math.min(256, this.results.size());
		long bytes = 0;
		long begin = System.nanoTime();
		this.publishes = 0;
		while (!this.requestLater.isEmpty() && this.requestLater.peek()[0] <= frame) {
			long[] q = this.requestLater.poll();
			if (this.clip.resident((int) q[1], (int) q[2], (int) q[3])) this.request((int) q[1], (int) q[2], (int) q[3]);
		}
		while (budget-- > 0) {
			var result = this.results.poll();
			if (result == null) break;
			boolean installed = this.installOrHold(frame, result);
			if (!installed) break;
			bytes += result.data[3] * 4;
			if (bytes >= 4L * 1048576 || System.nanoTime() - begin >= 2_000_000L) break;
		}
		this.healthFrames++;
		long now=System.nanoTime();
		if(now-this.healthAt>=30_000_000_000L) {
			long quads=0;for(long[] level:this.len) for(long words:level) if(words>0) quads+=Math.max(0,(words-HDR_WORDS)/2);
			mcopt.metal.DiagnosticLog.printf(java.util.Locale.ROOT,"mcopt-lod: render %.1f fps, mesh %d tiles / %d quads, pending %d, arena %.1f/%.0f MiB, reused %d meshes, unchanged %d cells, GPU copies %d%n",
				this.healthFrames*1e9/(now-this.healthAt),this.installed,quads,this.pending(),this.usedMb(),this.arenaMb(),this.reusedMeshes,this.clip.unchangedCells,this.clip.pendingGpuCount());
			this.healthAt=now;this.healthFrames=0;
		}
	}

	private int publishes;

	private boolean installOrHold(long frame, LodMeshResults.Result result) {
		long[] r = result.data;
		LodClip.Snapshot snap = result.snapshot;
		long k = r[0]; int level = LodTile.levelOf(k), tx = LodTile.txOf(k), tz = LodTile.tzOf(k);
		int slot = this.clip.slot(tx, tz);
		// Reject obsolete/nonresident results before they can occupy a publication slot or be deferred.
		if (!this.clip.resident(level, tx, tz) || !this.jobs.current(k, r[1])
			|| snap != null && (snap.version <= this.publishedVer[level][slot] || snap.epoch != this.clip.epoch[level][slot])
			|| LodClip.PAIRS && snap == null && r[5] != this.clip.writing[level].get(slot)) {
			result.close(); return true;
		}
		if (snap != null && this.publishes >= PUBLISH_BUDGET) {
			this.results.offer(result); return false;
		}
		boolean reuse = this.sameMesh(r);
		if (!reuse && !this.canFit(r[3])) {
			this.startGrowth(); this.results.offer(result); return false;
		}
		try {
			if (LodClip.PAIRS) this.installPair(frame, r, snap, reuse);
			else this.install(frame, r, false, reuse);
		} finally { result.close(); }
		return true;
	}

	/**
	 * PAIRS: a result meshed from a snapshot publishes the snapshot's words and installs its mesh in one step (then the
	 * neighbors' border walls are meshed again against them); one meshed from the clipmap installs only if the words it
	 * read are still the live ones.
	 */
	private void installPair(long frame, long[] r, LodClip.Snapshot snap, boolean reuse) {
		long k = r[0];
		int level = LodTile.levelOf(k), tx = LodTile.txOf(k), tz = LodTile.tzOf(k);
		if (!this.clip.resident(level, tx, tz)) return;
		int slot = this.clip.slot(tx, tz);
		if (snap != null) {
			if (snap.version <= this.publishedVer[level][slot]) return;
			if (!this.clip.publish(snap)) return;
			if (this.snaps.remove(k, snap)) snap.release();
			this.publishedVer[level][slot] = snap.version;
			this.publishes++;
			this.published.incrementAndGet();
			r[5] = this.clip.writing[level].get(slot);
			this.install(frame, r, true, reuse);
			int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			for (int[] n : d) {
				if (!this.clip.resident(level, tx + n[0], tz + n[1])) continue;
				if (LodPublish.ON) this.requestLater.add(new long[] {frame + RING, level, tx + n[0], tz + n[1]});
				else this.request(level, tx + n[0], tz + n[1]);
			}
			return;
		}
		if (r[5] != this.clip.writing[level].get(slot)) return;
		this.install(frame, r, true, reuse);
	}


	private void install(long frame, long[] r, boolean checked, boolean reuse) {
		long k = r[0];
		int level = LodTile.levelOf(k), tx = LodTile.txOf(k), tz = LodTile.tzOf(k);
		if (!this.clip.resident(level, tx, tz)) return;
		int slot = this.clip.slot(tx, tz);
		// an older job finishing after a newer one keeps the newer mesh (PAIRS: the word versions decide, checked by the caller)
		if (!checked && this.key[level][slot] == k && this.seq[level][slot] > r[1]) return;
		long words = r[3];
		if (reuse) {
			// Lighting/color updates publish new words, but can keep the existing geometry and arena allocation.
			this.seq[level][slot]=r[1];this.builtNeighbors[level][slot]=(int)r[4];this.builtVer[level][slot]=r[5];
			this.reusedMeshes++;
			return;
		}
		long at = this.allocate(words);
		if (at < 0) {
			System.out.println("mcopt-lod: mesh arena full (" + (this.arenaWords >> 18) + " MB); tile L" + level + " " + tx + "," + tz + " not drawn");
			return;
		}
		MemoryUtil.memCopy(r[2], this.arenaAddr + at * 4, words * 4);
		LodArenaGrowth.Prepared target = this.growth == null ? null : this.growth.ready();
		if (target != null) MemoryUtil.memCopy(r[2], target.address() + at * 4, words * 4);
		boolean fresh = this.off[level][slot] < 0 || this.key[level][slot] != k;
		// the position-keyed lists: the blocks whose quads or occluders differ from the mesh this one replaces (a tile new to
		// the slot: all of them)
		if (this.pk != null) {
			if (this.off[level][slot] >= 0 && this.key[level][slot] == k) this.changedBlocks(level, tx, tz, this.arenaAddr + this.off[level][slot] * 4, r[2]);
			else this.pk.tileChanged(level, tx, tz);
		}
		if (this.off[level][slot] >= 0) {
			this.releaseLater.add(new long[] {frame, this.off[level][slot], this.len[level][slot]});
			this.installed--;
		}
		this.off[level][slot] = at;
		this.len[level][slot] = words;
		this.key[level][slot] = k;
		this.seq[level][slot] = r[1];
		this.builtNeighbors[level][slot] = (int) r[4];
		this.builtVer[level][slot] = r[5];
		this.installed++;
		long e = this.tableHost + ((long) level * this.tps * this.tps + slot) * 16;
		MemoryUtil.memPutInt(e, (int) (at + 1));
		MemoryUtil.memPutInt(e + 4, tx);
		MemoryUtil.memPutInt(e + 8, tz);
		// (dissolve: a tile new to its slot starts its fade now; a remesh of the same tile keeps its stamp)
		if (DISSOLVE_MS <= 0) MemoryUtil.memPutInt(e + 12, 0);
		else if (fresh) MemoryUtil.memPutInt(e + 12, clockMs() | 1 << 24);
	}

	/**
	 * Tells the lists about each block of tile (tx, tz) whose quads (any group's), y range, occluders or group tops differ
	 * between the old mesh and the new one (header + quads each). Colors and textures are looked up by position when drawn,
	 * so a refresh that only changed those changes no block.
	 */
	private void changedBlocks(int level, int tx, int tz, long oldAddr, long newAddr) {
		long oldQ = oldAddr + (long) HDR_WORDS * 4, newQ = newAddr + (long) HDR_WORDS * 4;
		for (int b = 0; b < BPT * BPT; b++) {
			long oh = oldAddr + (long) b * 32 * 4, nh = newAddr + (long) b * 32 * 4;
			// the occluders (words 16-23: 16 parts' lowest solid tops, 12 bits each; the real terrain's run bottoms, nibbles in
			// words 16-31's bits 24-31): a top dropping or a bottom rising can uncover what the horizon hid
			boolean dropped = false;
			for (int k = 0; k < 16 && !dropped; k++) {
				int ow = MemoryUtil.memGetInt(oh + (16 + (k >> 1)) * 4L), nw = MemoryUtil.memGetInt(nh + (16 + (k >> 1)) * 4L);
				int ob = MemoryUtil.memGetInt(oh + (24 + (k >> 1)) * 4L), nb = MemoryUtil.memGetInt(nh + (24 + (k >> 1)) * 4L);
				int sh = 24 + (k & 1) * 4;
				dropped = (nw >>> ((k & 1) * 12) & 0xFFF) < (ow >>> ((k & 1) * 12) & 0xFFF)
					|| (nw >>> sh & 15 | (nb >>> sh & 15) << 4) > (ow >>> sh & 15 | (ob >>> sh & 15) << 4);
			}
			boolean same = MemoryUtil.memGetInt(oh + 15 * 4L) == MemoryUtil.memGetInt(nh + 15 * 4L);
			for (int w = 24; w < 32 && same; w++) same = MemoryUtil.memGetInt(oh + w * 4L) == MemoryUtil.memGetInt(nh + w * 4L);
			for (int g = 0; g < 15 && same; g++) {
				int ow = MemoryUtil.memGetInt(oh + g * 4L), nw = MemoryUtil.memGetInt(nh + g * 4L);
				int on = ow >>> 20, nn = nw >>> 20;
				if (on != nn) {
					same = false;
					break;
				}
				long os = oldQ + (long) (ow & 0xFFFFF) * 8, ns = newQ + (long) (nw & 0xFFFFF) * 8;
				for (int i = 0; i < 2 * on && same; i++) same = MemoryUtil.memGetInt(os + i * 4L) == MemoryUtil.memGetInt(ns + i * 4L);
			}
			if (same && !dropped) continue;
			double cell = 1 << level, bs = BLOCK * cell;
			double x0 = (tx * 64 + (b % BPT) * BLOCK) * cell, z0 = (tz * 64 + (b / BPT) * BLOCK) * cell;
			// the block and its neighbors (their border walls and skirt feet look at it)
			if (!same) this.pk.areaChanged(level, x0 - bs, z0 - bs, x0 + 2 * bs, z0 + 2 * bs);
			if (dropped) this.pk.occluderDropped(level, x0, z0, x0 + bs, z0 + bs);
		}
	}

	private void drop(int level, int slot) {
		if (this.pk != null && this.key[level][slot] >= 0) {
			long k = this.key[level][slot];
			this.pk.tileChanged(level, LodTile.txOf(k), LodTile.tzOf(k));
		}
		if (this.off[level][slot] >= 0) {
			// (the frame counter isn't at hand here: the next integrate stamps it)
			this.releaseLater.add(new long[] {this.lastFrame, this.off[level][slot], this.len[level][slot]});
			this.installed--;
		}
		this.off[level][slot] = -1;
		this.len[level][slot] = 0;
		this.key[level][slot] = -1;
		this.publishedVer[level][slot] = 0;
		MemoryUtil.memSet(this.tableHost + ((long) level * this.tps * this.tps + slot) * 16, 0, 16);
	}

	private long lastFrame;

	/** Whether tile (level, tx, tz) has its own mesh installed (it draws exactly). */
	boolean installed(int level, int tx, int tz) {
		int slot = this.clip.slot(tx, tz);
		return this.off[level][slot] >= 0 && this.key[level][slot] == LodTile.key(level, tx, tz);
	}

	/** Whether the installed mesh of a tile was built for older words than the clipmap holds now (seam probe). */
	boolean stale(int level, int tx, int tz) {
		int slot = this.clip.slot(tx, tz);
		return this.installed(level, tx, tz)
			&& this.builtVer[level][slot] != (LodClip.PAIRS ? this.clip.writing[level].get(slot) : this.clip.slotVer[level][slot]);
	}

	private boolean sameMesh(long[] r) {
		long k=r[0];int level=LodTile.levelOf(k),slot=this.clip.slot(LodTile.txOf(k),LodTile.tzOf(k));
		return this.off[level][slot]>=0 && this.key[level][slot]==k && this.len[level][slot]==r[3]
			&& MemoryUtil.memByteBuffer(this.arenaAddr+this.off[level][slot]*4,(int)(r[3]*4))
				.mismatch(MemoryUtil.memByteBuffer(r[2],(int)(r[3]*4)))<0;
	}

	private boolean canFit(long words) {
		words=(words+15)&~15L;
		for(long size:this.free.values())if(size>=words)return true;
		return false;
	}

	/** First fit never allocates a Metal buffer or copies an old arena on the render thread. */
	private long allocate(long words) {
		words=(words+15)&~15L;
		for(Map.Entry<Long,Long> f:this.free.entrySet()) {
			if(f.getValue()<words)continue;
			long at=f.getKey(),size=f.getValue();this.free.remove(at);
			if(size>words)this.free.put(at+words,size-words);
			this.usedWords+=words;return at;
		}
		return -1;
	}

	private void release(long at, long words) {
		words = (words + 15) & ~15L;
		this.usedWords -= words;
		// coalesce with the free neighbors
		Map.Entry<Long, Long> lo = this.free.floorEntry(at);
		if (lo != null && lo.getKey() + lo.getValue() == at) {
			at = lo.getKey();
			words += lo.getValue();
			this.free.remove(at);
		}
		Long hi = this.free.get(at + words);
		if (hi != null) {
			this.free.remove(at + words);
			words += hi;
		}
		this.free.put(at, words);
	}

	private void startGrowth() {
		if(this.growth!=null || System.nanoTime()<this.growthRetryAt || this.arenaWords*2>MAX_ARENA_WORDS)return;
		this.growth=new LodArenaGrowth(this.ctx,this.arenaWords,this.arenaWords*2);
	}

	/** Copy only live spans, with a byte/time budget. Installs during migration write both buffers. */
	private void advanceGrowth(long frame) {
		LodArenaGrowth g=this.growth;
		if(g==null)return;
		if(g.failure()!=null) {
			this.growthRetryAt=System.nanoTime()+1_000_000_000L;g.close();this.growth=null;
			mcopt.metal.DiagnosticLog.println("mcopt-lod: arena growth deferred after allocation failure: "+g.failure());return;
		}
		LodArenaGrowth.Prepared target=g.ready();if(target==null)return;
		long begin=System.nanoTime(),bytes=0;
		var event=LodArenaGrowth.span("copy",this.arenaWords,g.words);event.begin();
		int slots=this.tps*this.tps,total=slots*this.clip.levels;
		while(g.cursor<total && bytes<COPY_BYTES && System.nanoTime()-begin<COPY_NANOS) {
			int level=g.cursor/slots,slot=g.cursor%slots;
			if(this.off[level][slot]<0) {g.cursor++;g.progress=0;g.slotSequence=-1;continue;}
			if(g.slotSequence!=this.seq[level][slot]) {g.progress=0;g.slotSequence=this.seq[level][slot];}
			long size=this.len[level][slot]*4;
			long copy=Math.min(size-g.progress,COPY_BYTES-bytes);
			long at=this.off[level][slot]*4+g.progress;
			MemoryUtil.memCopy(this.arenaAddr+at,target.address()+at,copy);
			g.progress+=copy;bytes+=copy;g.copied+=copy;
			if(g.progress==size) {g.cursor++;g.progress=0;g.slotSequence=-1;}
		}
		event.copiedBytes=bytes;event.success=true;event.end();event.commit();
		if(g.cursor<total)return;
		var publish=LodArenaGrowth.span("publish",this.arenaWords,g.words);publish.begin();
		this.buffersLater.add(new long[]{frame,this.arenaBuf});
		this.release(this.arenaWords,g.words-this.arenaWords);this.usedWords+=g.words-this.arenaWords;
		this.arenaBuf=target.buffer();this.arenaAddr=target.address();this.arenaWords=g.words;
		g.adopt();this.growth=null;
		publish.copiedBytes=g.copied;publish.success=true;publish.end();publish.commit();
		mcopt.metal.DiagnosticLog.println("mcopt-lod: mesh arena grown to "+(this.arenaWords>>18)+" MB, copied "+g.copied+" live bytes");
	}

	boolean growing() { return this.growth!=null; }

	long arena() {
		return this.arenaBuf;
	}

	/** This frame's copy of the tile table. */
	long table(long frame) {
		this.lastFrame = frame;
		long b = this.tableBufs[(int) (frame % RING)];
		MemoryUtil.memCopy(this.tableHost, LodNative.contents(b), this.tableBytes);
		this.current = b;
		return b;
	}

	private long current;

	/** The table copy this frame's cull used (the draw's vertex stage reads it too: skirt feet). */
	long currentTable() {
		return this.current;
	}

	/** Threads of the block cull: a block of every level's window each. */
	int blocks() {
		int bpw = this.tps * BPT;
		return this.clip.levels * bpw * bpw;
	}

	/** MeshFrame (columns.metal): the camera, the mask, the windows and switch distances. */
	void pack(org.joml.Matrix4f viewProj, double camX, double camY, double camZ, double reach, boolean maskOn, int maskX, int maskZ, int maskSize,
		int maskWords, float maskDist) {
		long f = this.frame;
		MemoryUtil.memSet(f, 0, FRAME_BYTES);
		viewProj.getToAddress(f);
		int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
		MemoryUtil.memPutFloat(f + 64, (float) (camX - bx));
		MemoryUtil.memPutFloat(f + 68, (float) (camY - by));
		MemoryUtil.memPutFloat(f + 72, (float) (camZ - bz));
		MemoryUtil.memPutFloat(f + 76, (float) reach);
		MemoryUtil.memPutInt(f + 80, bx);
		MemoryUtil.memPutInt(f + 84, by);
		MemoryUtil.memPutInt(f + 88, bz);
		MemoryUtil.memPutInt(f + 92, this.clip.levels);
		MemoryUtil.memPutInt(f + 96, maskOn ? maskX : 0);
		MemoryUtil.memPutInt(f + 100, maskOn ? maskZ : 0);
		MemoryUtil.memPutInt(f + 104, maskOn ? maskSize : 0);
		MemoryUtil.memPutInt(f + 108, Math.max(1, maskWords));
		MemoryUtil.memPutFloat(f + 112, maskOn ? maskDist : 0);
		MemoryUtil.memPutInt(f + 128, this.tps);
		MemoryUtil.memPutInt(f + 132, BLOCK);
		// (with the lists, the culls' own instances stay under their sectors' stand-ins, whichever path draws)
		MemoryUtil.memPutInt(f + 136, LodPk.ENABLED ? LodPk.STAND_BASE : MAX_INSTANCES);
		MemoryUtil.memPutInt(f + 140, MAX_PLANT_INSTANCES);
		// the horizon cull: bands from 48 blocks, 128 of them out to twice the reach
		MemoryUtil.memPutInt(f + 392, HORIZON ? (REAL_OCC ? (REAL_OCC_HEIGHT ? 49 : 17) : 1) | (HZ_MARK ? 8 : 0) : 0);
		MemoryUtil.memPutFloat(f + 400, 48.0F);
		MemoryUtil.memPutFloat(f + 404, (float) (128.0 / Math.log(Math.max(2.0, reach * 2.0 / 48.0))));
		MemoryUtil.memPutInt(f + 416, MAX_SURVIVORS);
		MemoryUtil.memPutInt(f + 420, MAX_LISTED);
		for (int l = 0; l < this.clip.levels; l++) {
			MemoryUtil.memPutInt(f + 144 + l * 16L, this.clip.winTx[l]);
			MemoryUtil.memPutInt(f + 148 + l * 16L, this.clip.winTz[l]);
			MemoryUtil.memPutFloat(f + 336 + l * 4L, this.clip.switchDist[l]);
		}
	}

	double arenaMb() {
		return this.arenaWords * 4 / 1048576.0;
	}

	double usedMb() {
		return this.usedWords * 4 / 1048576.0;
	}

	int pending() {
		return this.jobs.size() + this.results.size();
	}

	void close() {
		if (this.closed) return;
		this.closed = true;
		this.results.close();
		if (this.growth != null) { this.growth.close(); this.growth = null; }
		for (Thread t : this.workers) t.interrupt();
		this.clip.listener = null;
		// A dimension switch must not release clipmap buffers while a native mesh job still reads them.
		boolean interrupted = false;
		for (Thread t : this.workers) {
			while (t.isAlive()) try { t.join(); }
			catch (InterruptedException e) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
		this.jobs.clear();
		for (LodClip.Snapshot s : this.snaps.values()) s.release();
		this.snaps.clear();
		MemoryUtil.nmemFree(this.tableHost);
		MemoryUtil.nmemFree(this.frame);
	}

	/** GPU buffers, for release by the owner once frames in flight are done (the arena's current and old ones included). */
	long[] buffers() {
		long[] b = new long[RING + 7 + this.buffersLater.size()];
		int i = 0;
		for (long t : this.tableBufs) b[i++] = t;
		b[i++] = this.arenaBuf;
		b[i++] = this.argsBuf;
		b[i++] = this.instBuf;
		b[i++] = this.plantBuf;
		b[i++] = this.survBuf;
		b[i++] = this.horizonBuf;
		b[i++] = this.listBuf;
		for (long[] o : this.buffersLater) b[i++] = o[1];
		return b;
	}
}
