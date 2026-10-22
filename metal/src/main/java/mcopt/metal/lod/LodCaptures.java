package mcopt.metal.lod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.LongFunction;
import java.util.function.BiConsumer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Authoritative visited columns, independent of a clipmap tile's residency or generation order. */
final class LodCaptures implements AutoCloseable {
	static final int CELLS = 256, PLANES = 9, MEMORY_CHUNKS = 256;
	static final int GEOM = 0, COLOR = 1, CROWN = 2, MATERIAL = 3, LIGHT = 4, RUNS = 5, PLANT_A = 6, PLANT_B = 7, COARSE_COLOR = 8;
	private static final int MAGIC = 0x4D434350, FORMAT = 6;

	/** Packed fine columns plus the coarse wall color; no live chunk, block-state array or biome retained. */
	static final class Capture {
		final int x,z;
		final int[][] planes;
		private final boolean legacyGap;
		private final int[][][] mips=new int[5][][];
		Capture(int x,int z,int[][] planes) {
			this(x,z,planes,false);
		}
		private Capture(int x,int z,int[][] planes,boolean legacyGap) {
			if (planes.length != PLANES) throw new IllegalArgumentException("Capture planes");
			for (int[] plane : planes) if (plane.length != CELLS) throw new IllegalArgumentException("Capture cells");
			this.x=x;this.z=z;this.planes=planes;this.legacyGap=legacyGap;
		}
		int x() { return this.x; } int z() { return this.z; } int[][] planes() { return this.planes; }
		synchronized int[][] atLevel(int level) {
			if(level==0) return this.planes;
			int l=Math.min(4,level);
			if(this.mips[l]==null) this.mips[l]=LodCaptureMips.reduce(this.planes,l);
			return this.mips[l];
		}
		long key() { return LodForest.chunkKey(this.x, this.z); }
	}
	/** Worker-prepared data shared by persistence and live clipmap installation. */
	record Prepared(LodChunks.Summary summary, Capture capture) { }

	static Prepared prepare(LodChunks.Summary summary, int levels) {
		Capture capture = pack(summary);
		for (int level = 1; level < Math.min(levels, 5); level++) capture.atLevel(level);
		return new Prepared(summary, capture);
	}

	private record Computed(Capture capture, Runnable complete) { }
	private record Request(Supplier<Computed> compute, boolean raw) { }
	@FunctionalInterface interface CaptureWriter { void write(Capture capture) throws IOException; }
	static final int RAW_LIMIT = 64, WRITE_LIMIT = 128;
	private final Path directory;
	private final int levels, memoryLimit;
	private final boolean disk;
	private final CaptureWriter output;
	private final LinkedHashMap<Long, Capture> memory = new LinkedHashMap<>(32, 0.75F, true);
	private final Object[] readers = new Object[64];
	private final AtomicLong diskReads = new AtomicLong();
	/** Existing disk index also stores whether an older decoded capture needs a saved-only refresh. */
	private final ConcurrentHashMap<Long, Boolean> indexed = new ConcurrentHashMap<>();
	boolean refreshNeeded(long key) { return Boolean.TRUE.equals(this.indexed.get(key)); }
	private final ConcurrentHashMap<Long, Long> revisions = new ConcurrentHashMap<>();
	private final AtomicLong serial = new AtomicLong();
	private final ConcurrentHashMap<Long, Long> stamps = new ConcurrentHashMap<>();
	private long nextStamp;
	private final ConcurrentHashMap<Long, Request> pending = new ConcurrentHashMap<>();
	private final LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>();
	/** Includes the in-flight write until its atomic replacement completes, so eviction cannot expose an older file. */
	private final LinkedHashMap<Long, Capture> writes = new LinkedHashMap<>();
	private final CompletableFuture<Void> ready = new CompletableFuture<>();
	private final Thread writer, diskWriter;
	private int rawPending;
	private boolean diskClosing;
	private volatile boolean closing;

	LodCaptures(Path cache, int levels, boolean disk) { this(cache, levels, disk, MEMORY_CHUNKS); }

	LodCaptures(Path cache, int levels, boolean disk, int memoryLimit) {
		this(cache, levels, disk, memoryLimit, null);
	}

	LodCaptures(Path cache, int levels, boolean disk, int memoryLimit, CaptureWriter output) {
		this.directory = cache.resolve("real-chunks");
		this.levels = levels;
		this.disk = disk;
		this.output = output == null ? this::write : output;
		this.memoryLimit = Math.max(1, memoryLimit);
		for (int i = 0; i < this.readers.length; i++) this.readers[i] = new Object();
		this.writer = new Thread(this::work, "mcopt-lod-captures");
		this.writer.setDaemon(true);
		this.writer.setPriority(Thread.MIN_PRIORITY + 1);
		this.diskWriter = disk ? new Thread(this::writeLoop, "mcopt-lod-capture-io") : null;
		if (this.diskWriter != null) {
			this.diskWriter.setDaemon(true);
			this.diskWriter.setPriority(Thread.MIN_PRIORITY + 1);
			this.diskWriter.start();
		}
		this.writer.start();
	}

	/** Coalesces light/edit bursts before summarizing. The writer drains accepted snapshots when the world closes. */
	void capture(LodChunks.Snapshot snapshot, Consumer<LodChunks.Summary> complete) {
		this.capturePrepared(snapshot, prepared -> complete.accept(prepared.summary));
	}

	void capturePrepared(LodChunks.Snapshot snapshot, Consumer<Prepared> complete) {
		this.submit(LodForest.chunkKey(snapshot.chunkX(), snapshot.chunkZ()), -1, stamp -> () -> {
			LodChunks.Summary summary = LodChunks.summarize(snapshot);
			Prepared prepared = prepare(summary, this.levels);
			return new Computed(prepared.capture, () -> complete.accept(prepared));
		});
	}

	/** At most 64 detached raw chunks queued; an already queued coordinate replaces its older copy. */
	boolean captureDeferred(long key, Supplier<LodChunks.Snapshot> snapshot, BiConsumer<LodChunks.Summary, Boolean> complete) {
		return this.captureDeferredPrepared(key, snapshot, (prepared, valid) -> complete.accept(prepared.summary, valid));
	}

	synchronized boolean captureDeferredPrepared(long key, Supplier<LodChunks.Snapshot> snapshot, BiConsumer<Prepared, Boolean> complete) {
		Request previous = this.pending.get(key);
		if (this.rawPending >= RAW_LIMIT && (previous == null || !previous.raw)) return false;
		this.submit(key, -1, stamp -> () -> {
			LodChunks.Snapshot s = snapshot.get();
			boolean valid = !LodConfig.LIGHTING;
			if (LodConfig.LIGHTING) { valid = true; for (boolean v : s.lightValid()) valid &= v; }
			boolean ready = valid;
			LodChunks.Summary summary = LodChunks.summarize(s);
			Prepared prepared = prepare(summary, this.levels);
			return new Computed(prepared.capture, () -> complete.accept(prepared, ready));
		}, true);
		return true;
	}

	/** Also used by the disk/eviction regression checks without a live world. Ownership of the immutable planes transfers. */
	void put(Capture capture) { this.submit(capture.key(), () -> new Computed(capture, () -> { })); }

	/** A saved snapshot must not overwrite a live edit received while the disk read was in flight. */
	long captureIfUnchanged(LodChunks.Snapshot snapshot, long expected, BiConsumer<LodChunks.Summary, Long> complete) {
		return this.captureIfUnchangedPrepared(snapshot, expected, (prepared, stamp) -> complete.accept(prepared.summary, stamp));
	}

	long captureIfUnchangedPrepared(LodChunks.Snapshot snapshot, long expected, BiConsumer<Prepared, Long> complete) {
		return this.submit(LodForest.chunkKey(snapshot.chunkX(), snapshot.chunkZ()), expected, stamp -> () -> {
			LodChunks.Summary summary = LodChunks.summarize(snapshot);
			Prepared prepared = prepare(summary, this.levels);
			return new Computed(prepared.capture, () -> complete.accept(prepared, stamp));
		});
	}

	boolean has(long key) { return this.indexed.containsKey(key) || this.pending.containsKey(key); }
	long stamp(long key) { return this.stamps.getOrDefault(key, 0L); }
	int pendingCount() { return this.pending.size() + this.writePendingCount(); }
	synchronized int rawPendingCount() { return this.rawPending; }
	int writePendingCount() { synchronized (this.writes) { return this.writes.size(); } }
	boolean indexed() { return this.ready.isDone(); }

	private void submit(long key, Supplier<Computed> compute) {
		this.submit(key, -1, stamp -> compute);
	}

	private synchronized long submit(long key, long expected, LongFunction<Supplier<Computed>> compute) {
		return this.submit(key, expected, compute, false);
	}

	private synchronized long submit(long key, long expected, LongFunction<Supplier<Computed>> compute, boolean raw) {
		if (this.closing) throw new IllegalStateException("Capture store closed");
		if (expected >= 0 && this.stamp(key) != expected) return -1;
		long stamp = ++this.nextStamp;
		this.stamps.put(key, stamp);
		Request previous = this.pending.put(key, new Request(compute.apply(stamp), raw));
		if (raw) this.rawPending++;
		if (previous == null) this.queue.add(key);
		else if (previous.raw) this.rawPending--;
		return stamp;
	}

	private synchronized boolean remove(long key, Request request) {
		if (!this.pending.remove(key, request)) return false;
		if (request.raw) this.rawPending--;
		return true;
	}

	private void work() {
		try {
			if (this.disk && Files.isDirectory(this.directory)) try (var files = Files.newDirectoryStream(this.directory, "*.lodc")) {
				for (Path path : files) {
					String[] p = path.getFileName().toString().replace(".lodc", "").split("\\.");
					if (p.length != 2) continue;
					try { this.indexed.putIfAbsent(LodForest.chunkKey(Integer.parseInt(p[0]), Integer.parseInt(p[1])), false); }
					catch (NumberFormatException ignored) { }
				}
			}
		} catch (IOException e) { System.out.println("mcopt-lod: capture index: " + e); }
		finally { this.ready.complete(null); }
		while (!this.closing || !this.pending.isEmpty()) {
			Long key;
			try { key = this.queue.poll(100, TimeUnit.MILLISECONDS); }
			catch (InterruptedException e) { continue; }
			if (key == null) continue;
			Request request = this.pending.get(key);
			if (request == null) continue;
			try {
				Computed computed = request.compute.get();
				if (!this.remove(key, request)) { this.queue.add(key); continue; }
				Capture data = computed.capture;
				this.publish(key, data);
				computed.complete.run();
			} catch (RuntimeException e) {
				if (!this.remove(key, request) && this.pending.containsKey(key)) this.queue.add(key);
				System.out.println("mcopt-lod: capture failed: " + e);
			}
		}
	}

	/** Only the capture worker waits for a full disk stage; producers retain their existing bounded overload fallback. */
	private void publish(long key, Capture data) {
		boolean interrupted = false;
		synchronized (this.writes) {
			while (this.disk && this.writes.size() >= WRITE_LIMIT && !this.writes.containsKey(key)) {
				try { this.writes.wait(); } catch (InterruptedException e) { interrupted = true; }
			}
			// Install the pending write and new revision together. Readers never reopen an evicted, older disk revision.
			if (this.disk) this.writes.put(key, data);
			synchronized (this.memory) {
				this.memory.put(key, data); this.indexed.put(key, data.legacyGap);
				long version = this.serial.incrementAndGet();
				for (int l = 0; l < this.levels; l++) this.revisions.put(tileKey(l, data.x, data.z), version);
				this.trim();
			}
			this.writes.notifyAll();
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private void writeLoop() {
		while (true) {
			long key; Capture data;
			synchronized (this.writes) {
				while (this.writes.isEmpty() && !this.diskClosing) {
					try { this.writes.wait(); } catch (InterruptedException ignored) { }
				}
				if (this.writes.isEmpty()) return;
				var next = this.writes.entrySet().iterator().next(); key = next.getKey(); data = next.getValue();
			}
			try { this.output.write(data); }
			catch (IOException | RuntimeException e) { System.out.println("mcopt-lod: capture write failed for " + data.x + "," + data.z + ": " + e); }
			synchronized (this.writes) {
				Capture current = this.writes.remove(key);
				if (current != null && current != data) this.writes.put(key, current); // Newer edit gets a turn after other queued coordinates.
				this.writes.notifyAll();
			}
		}
	}

	private static long tileKey(int level, int cx, int cz) {
		return LodTile.key(level, Math.floorDiv(cx, 4 << level), Math.floorDiv(cz, 4 << level));
	}

	long revision(long tile) { return this.revisions.getOrDefault(tile, 0L); }
	void awaitReady() { this.ready.join(); }
	long diskReadCount() { return this.diskReads.get(); }

	/** Worker-only: loading a sparse correction never generates or requests a Minecraft chunk. */
	void overlay(long key, int[] g, int[] color, int[] crowns, int[] texture, int[] runs, int[] plants) {
		this.awaitReady();
		int level = LodTile.levelOf(key), step = 1 << level;
		int x0 = LodTile.txOf(key) * (64 << level), z0 = LodTile.tzOf(key) * (64 << level);
		// Visit every source chunk once, even if other workers evict it between adjacent rows.
		int shift = Math.min(level, 4), size = 16 >> shift;
		for (int row = 0; row < 64; row += size) for (int col = 0; col < 64; col += size) {
			int x = x0 + col * step, z = z0 + row * step;
			Capture source = this.get(LodForest.chunkKey(x >> 4, z >> 4));
			if (source == null) continue;
			int[][] p = source.atLevel(level);
			for (int dz = 0; dz < size; dz++) for (int dx = 0; dx < size; dx++) {
				int i = (row + dz) * 64 + col + dx, k = dz * size + dx;
				g[i] = level == 0 ? p[GEOM][k] : LodClip.coarseGeom(p[GEOM][k]);
				if (crowns == null) g[i] = LodClip.withoutCrown(g[i]);
				else crowns[i] = p[CROWN][k];
				color[i] = p[COLOR][k];
				if (runs != null) runs[i] = p[RUNS][k];
				if (texture != null) {
					texture[i] = p[MATERIAL][k];
					if (texture.length > LodTile.CELLS) texture[LodTile.CELLS + i] = p[LIGHT][k];
				}
				if (plants != null) {
					plants[i] = p[PLANT_A][k];
					plants[LodTile.CELLS + i] = p[PLANT_B][k];
				}
			}
		}
	}

	private Capture get(long key) {
		synchronized (this.memory) {
			Capture data = this.memory.get(key);
			if (data != null) return data;
		}
		// A bounded stripe table avoids duplicate in-flight reads without retaining a lock per saved chunk.
		// The writer never takes these locks; disk I/O never holds the resident-cache monitor.
		long hash = key ^ (key >>> 33); hash *= 0xff51afd7ed558ccdL; hash ^= hash >>> 33;
		synchronized (this.readers[(int) hash & (this.readers.length - 1)]) {
			return this.load(key);
		}
	}

	private Capture load(long key) {
		while (this.indexed.containsKey(key)) {
			synchronized (this.memory) {
				Capture data = this.memory.get(key);
				if (data != null) return data;
			}
			if (!this.disk) return null;
			// Sample the revision before consulting pending writes: a publication between that lookup and opening
			// the old file must change the revision we validate afterwards, even if the new entry is then evicted.
			long tile = tileKey(0, (int) (key >> 32), (int) key), revision = this.revision(tile);
			synchronized (this.writes) {
				Capture data = this.writes.get(key);
				if (data != null) return data;
			}
			Capture data;
			try { data = this.read((int) (key >> 32), (int) key); }
			catch (IOException | RuntimeException e) {
				synchronized (this.memory) {
					if (this.revision(tile) != revision) continue;
					if (this.memory.containsKey(key)) return this.memory.get(key);
					this.indexed.remove(key); // Do not reopen a broken file for every cell; a fresh capture restores its index.
				}
				return null;
			}
			synchronized (this.memory) {
				Capture newer = this.memory.get(key);
				if (newer != null) return newer;
				// Another reader can evict the new entry while this disk read is in flight. Never reinsert an older file.
				if (this.revision(tile) != revision) continue;
				this.memory.put(key, data);
				if (data.legacyGap) this.indexed.replace(key, true);
				this.trim();
			}
			return data;
		}
		return null;
	}

	private void trim() {
		synchronized (this.memory) {
			while (this.memory.size() > this.memoryLimit) {
				Long key = this.memory.keySet().iterator().next();
				this.memory.remove(key);
				if (!this.disk) this.indexed.remove(key);
			}
		}
	}

	static Capture pack(LodChunks.Summary summary) {
		int[][] p = new int[PLANES][CELLS];
		int[] texture = new int[2 * CELLS], plants = new int[2 * CELLS];
		for (int i = 0; i < CELLS; i++) {
			LodField.packCapturedCell(0, summary, i, i, p[GEOM], p[COLOR], p[CROWN], texture, p[RUNS], plants, CELLS);
			p[COARSE_COLOR][i] = (p[GEOM][i]&LodClip.GEOM_CROWN)!=0 ? p[COLOR][i]
				: LodClip.colorWord(summary.top()[i], LodColors.mix(summary.side()[i], summary.below()[i], 0.6F));
		}
		System.arraycopy(texture, 0, p[MATERIAL], 0, CELLS);
		System.arraycopy(texture, CELLS, p[LIGHT], 0, CELLS);
		System.arraycopy(plants, 0, p[PLANT_A], 0, CELLS);
		System.arraycopy(plants, CELLS, p[PLANT_B], 0, CELLS);
		return new Capture(summary.chunkX(), summary.chunkZ(), p);
	}

	private Path file(int x, int z) { return this.directory.resolve(x + "." + z + ".lodc"); }

	private void write(Capture capture) throws IOException {
		Files.createDirectories(this.directory);
		Path file = this.file(capture.x, capture.z), tmp = file.resolveSibling(file.getFileName() + ".tmp");
		var local = new LinkedHashMap<Integer, Integer>();
		local.put(0, 0);
		for (int i = 0; i < CELLS; i++) {
			int t = capture.planes[MATERIAL][i], a = capture.planes[PLANT_A][i];
			for (int id : new int[] {t & 1023, t >>> 10 & 1023, t >>> 20 & 1023, a & 1023, a >>> 10 & 1023}) local.putIfAbsent(id, local.size());
		}
		Deflater compressor = new Deflater(Deflater.BEST_SPEED);
		// Buffer uncompressed words too: otherwise each writeInt enters the native compressor.
		try (var out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)), compressor), 16384))) {
			out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeInt(capture.x); out.writeInt(capture.z);
			out.writeInt(local.size());
			for (int id : local.keySet()) out.writeUTF(LodPalette.nameOf(id));
			for (int plane = 0; plane < PLANES; plane++) for (int i = 0; i < CELLS; i++) {
				int v = capture.planes[plane][i];
				if (plane == MATERIAL) v = local.get(v & 1023) | local.get(v >>> 10 & 1023) << 10 | local.get(v >>> 20 & 1023) << 20 | v & 0xC0000000;
				else if (plane == PLANT_A) v = local.get(v & 1023) | local.get(v >>> 10 & 1023) << 10 | v & ~0xFFFFF;
				out.writeInt(v);
			}
		} finally { compressor.end(); }
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private Capture read(int x, int z) throws IOException {
		this.diskReads.incrementAndGet();
		// A buffer below the inflater only batches disk reads; this one batches decompression.
		try (var in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(this.file(x, z)))), 16384))) {
			if (in.readInt() != MAGIC) throw new IOException("Capture header");
			int format=in.readInt(); // v2 adds segmented interval flags; v1 fine columns still reduce without recapture.
			if (format<1 || format>FORMAT || in.readInt() != x || in.readInt() != z) throw new IOException("Capture header");
			int n = in.readInt();
			if (n < 1 || n > LodPalette.MAX) throw new IOException("Capture palette");
			int[] palette = new int[n];
			for (int i = 0; i < n; i++) {
				palette[i] = LodPalette.idOf(in.readUTF());
				var state=LodPalette.stateOf(palette[i]);
				if(format<3 && state!=null && LodChunks.chorus(state)) throw new IOException("Legacy chorus capture requires saved-chunk refresh");
				if(format<5 && state!=null && (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)))
					throw new IOException("Legacy tree capture requires vertical-layer refresh");
			}
			int[][] p = new int[PLANES][CELLS];
			for (int plane = 0; plane < PLANES; plane++) for (int i = 0; i < CELLS; i++) {
				int v = in.readInt();
				if (plane == MATERIAL) v = remap(palette, v & 1023) | remap(palette, v >>> 10 & 1023) << 10 | remap(palette, v >>> 20 & 1023) << 20 | v & 0xC0000000;
				else if (plane == PLANT_A) v = remap(palette, v & 1023) | remap(palette, v >>> 10 & 1023) << 10 | v & ~0xFFFFF;
				p[plane][i] = v;
			}
			if(format<5) for(int t:p[MATERIAL]) if((t&LodClip.TEX_OVERHANG)!=0)
				throw new IOException("Legacy overhang capture requires vertical-layer refresh");
			if(format<5) for(int c:p[CROWN]) if((c&LodClip.CROWN_BOUNDED)!=0)
				throw new IOException("Legacy finite capture requires complete lower-body refresh");
			if(format<5) for(int g:p[GEOM]) if((g&(LodClip.GEOM_CROWN|LodClip.GEOM_WET))==0 && (g>>>25)>0 && (g>>>25)<127)
				throw new IOException("Legacy finite surface run requires overhang refresh");
			// Keep old visits visible while the budgeted saved-import lane rechecks potentially unbounded columns.
			// Their packed height alone cannot tell an ordinary surface from a roof whose long gap was rejected.
			boolean legacyGap = false;
			if (format < 6) for (int i=0;i<CELLS;i++) if (
				((p[GEOM][i] & (LodClip.GEOM_CROWN|LodClip.GEOM_WET))==0 && (p[CROWN][i]&LodClip.CROWN_BOUNDED)==0)
				|| (p[CROWN][i]&(LodClip.CROWN_WET|LodClip.CROWN_SEGMENTED))==(LodClip.CROWN_WET|LodClip.CROWN_SEGMENTED)) {
				legacyGap=true;break;
			}
			return new Capture(x, z, p, legacyGap);
		}
	}

	private static int remap(int[] palette, int i) throws IOException {
		if (i >= palette.length) throw new IOException("Capture palette index");
		return palette[i];
	}

	@Override public void close() {
		synchronized (this) { this.closing = true; }
		boolean interrupted = false;
		for (;;) try { this.writer.join(); break; }
		catch (InterruptedException e) { interrupted = true; }
		if (this.diskWriter != null) {
			synchronized (this.writes) { this.diskClosing = true; this.writes.notifyAll(); }
			for (;;) try { this.diskWriter.join(); break; }
			catch (InterruptedException e) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}
}
