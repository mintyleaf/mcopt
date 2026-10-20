package mcopt.metal.lod;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/** Singleplayer population: separate bounded lanes for saved imports, structure probes and vanilla FULL requests. */
public final class LodRefinement implements AutoCloseable {
	private static final ConcurrentHashMap<ServerLevel, LodRefinement> ACTIVE = new ConcurrentHashMap<>();
	static TicketType newTicket() { return new TicketType(0L, TicketType.FLAG_LOADING); }
	private final TicketType ticket = newTicket();
	private record Focus(int x, int z, int rd, double fx, double fz, double frameMs, boolean nearbyBusy, long time) { }
	private record Import(long key, long stamp, LodChunks.Snapshot snapshot) { }
	private static final class Request {
		final ChunkPos pos;
		final boolean probe;
		boolean scout;
		final int radius;
		final long stamp, since, tick;
		final LodRefineGate<Object> gate = new LodRefineGate<>();
		CompletableFuture<Object> placeholder;
		Request(ChunkPos pos, boolean probe, boolean scout, int radius, long stamp, long since, long tick) {
			this.pos = pos; this.probe = probe; this.scout = scout; this.radius = radius;
			this.stamp = stamp; this.since = since; this.tick = tick;
		}
	}
	private final ServerLevel level;
	private final int cavernY, scoutRadius, importRadius, reachRadius;
	private final LodCaptures captures;
	private final java.util.function.BiConsumer<LodChunks.Snapshot, Long> accept;
	private final BooleanSupplier congested;
	private final LodRefinePlan plan = new LodRefinePlan(LodConfig.REFINE_RADIUS);
	private final LinkedHashMap<Long, Request> requests = new LinkedHashMap<>();
	private final LodRefineBudget budget = new LodRefineBudget();
	private final LodHeap heap = new LodHeap();
	private LodPoiPlan poi;
	private int fullStarts;
	private final ArrayBlockingQueue<Import> imports = new ArrayBlockingQueue<>(16);
	private volatile Set<Long> saved = ConcurrentHashMap.newKeySet();
	private final Set<Long> needsFull = ConcurrentHashMap.newKeySet();
	private final ArrayDeque<ChunkPos> retiring = new ArrayDeque<>();
	private final Thread reader;
	private final Path region;
	private volatile Focus focus;
	private volatile boolean closed, backgroundAllowed;
	private long lastFrame, nextStart, nextStatus, nextDebug;
	private double frameMs = 16.7;
	private long retained = -1;
	private int imported, generated;
	private volatile List<String> debug = List.of("LOD population: starting");

	List<String> debugLines() { return this.debug; }
	int activeFull() { return count(false); }
	int activeProbes() { return count(true); }
	private int count(boolean probe) {
		int count = 0; for (Request r : requests.values()) if (r.probe == probe) count++; return count;
	}

	private void publishDebug() {
		long now = System.nanoTime();
		if (now < nextDebug) return;
		nextDebug = now + 500_000_000L;
		Focus f = focus;
		String state = closed ? "stopped" : !active(f) ? "waiting for player" : level.getServer().isPaused() ? "paused"
			: !captures.indexed() ? "indexing saved captures" : congested.getAsBoolean() ? "waiting for capture/upload queue"
			: LodConfig.REFINE_RADIUS == 0 ? "new generation disabled" : budget.reason();
		if (active(f) && budget.ready(now)) {
			if (retiring.size() >= retireLimit()) state = "waiting for chunk unloading";
			else if (level.getChunkSource().getLoadedChunksCount() > LodRefineBudget.chunkLimit(f.rd)) state = "waiting for player chunks";
			else if (poi != null && poi.pending() >= footprintHighWater()) state = "building confirmed footprints; probes paused";
			else if (LodConfig.REFINE_INTERVAL_MS > 0 && activeFull() < budget.fullLimit(now)
				&& !budget.generationReady(now, 0, f.rd)) state = "FULL start spacing; probes independent";
		}
		Runtime runtime = Runtime.getRuntime();
		debug = List.of("LOD population: " + state + " (" + LodConfig.WORKLOAD.label() + "; admit " + budget.fullLimit(now) + " FULL/" + budget.probeLimit(now) + " probes)",
			"LOD work: FULL " + activeFull() + "/" + LodConfig.WORKLOAD.full + ", probes " + activeProbes() + "/" + LodConfig.WORKLOAD.probes
				+ "; import queue " + imports.size() + ", radius " + importRadius + "c",
			"LOD structures: " + (poi == null ? 0 : poi.validated) + " probed, " + (poi == null ? 0 : poi.structures)
				+ " confirmed, " + (poi == null ? 0 : poi.pending()) + " footprint chunks queued; radius " + scoutRadius + "c",
			"LOD populated: " + generated + " FULL, " + imported + " saved imports; retained heap "
				+ (retained < 0 ? "unknown" : (retained >> 20) + " MiB") + "/" + (runtime.maxMemory() >> 20) + " MiB");
	}

	LodRefinement(ServerLevel level, LodCaptures captures, java.util.function.BiConsumer<LodChunks.Snapshot, Long> accept, BooleanSupplier congested) {
		this(level, captures, accept, congested, level.dimensionType().hasCeiling() ? 64 : LodCaverns.NONE);
	}

	LodRefinement(ServerLevel level, LodCaptures captures, java.util.function.BiConsumer<LodChunks.Snapshot, Long> accept, BooleanSupplier congested, int cavernY) {
		this.level = level; this.cavernY = cavernY; this.captures = captures; this.accept = accept; this.congested = congested;
		reachRadius = effectiveRadius(Integer.MAX_VALUE, LodConfig.RADIUS_CHUNKS, level.dimensionType().hasCeiling());
		scoutRadius = effectiveRadius(LodConfig.SCOUT_RADIUS, LodConfig.RADIUS_CHUNKS, level.dimensionType().hasCeiling());
		importRadius = effectiveRadius(LodConfig.IMPORT_RADIUS, LodConfig.RADIUS_CHUNKS, level.dimensionType().hasCeiling());
		region = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
		reader = new Thread(this::readSaved, "mcopt-lod-saved-import");
		reader.setDaemon(true); reader.setPriority(Thread.MIN_PRIORITY + 1);
		ACTIVE.put(level, this);
		if (LodConfig.IMPORT_SAVED && importRadius > 0) reader.start();
		System.out.println("mcopt-lod: population " + LodConfig.WORKLOAD.label() + ": up to " + LodConfig.WORKLOAD.full + " FULL + "
			+ LodConfig.WORKLOAD.probes + " probes, start spacing " + LodConfig.REFINE_INTERVAL_MS + "ms, scout " + scoutRadius + "c, import " + importRadius + "c");
	}

	static int effectiveRadius(int configured, int reach, boolean ceiling) { return Math.max(0, Math.min(configured, Math.min(reach, ceiling ? 64 : Integer.MAX_VALUE))); }

	void focus(double x, double z, double fx, double fz, int rd) {
		long now = System.nanoTime();
		if (lastFrame != 0) frameMs += 0.05 * (Math.min(1000, (now - lastFrame) / 1e6) - frameMs);
		lastFrame = now;
		focus = new Focus(((int)Math.floor(x)) >> 4, ((int)Math.floor(z)) >> 4, rd, fx, fz, frameMs, LodYield.ON && LodYield.populationPressure, now);
	}
	void pause() {
		if (focus == null || closed) return;
		focus = null; level.getServer().execute(this::tick);
	}
	public static void tick(ServerLevel level) { LodRefinement work = ACTIVE.get(level); if (work != null) work.tick(); }
	public static void stop(ServerLevel level) { LodRefinement work = ACTIVE.get(level); if (work != null) work.close(); }
	private static long key(ChunkPos pos) { return LodForest.chunkKey(pos.x(), pos.z()); }
	private static boolean near(ChunkPos p, Focus f) { return f != null && Math.abs(p.x() - f.x) <= f.rd + 2 && Math.abs(p.z() - f.z) <= f.rd + 2; }
	private static boolean active(Focus f) { return f != null && System.nanoTime() - f.time < 2_000_000_000L; }
	private static boolean within(long key, Focus f, int radius) {
		if (f == null) return false;
		long dx = (int)(key >> 32) - (long)f.x, dz = (int)key - (long)f.z;
		return dx * dx + dz * dz <= (long)radius * radius;
	}
	private int retireLimit() { return 4 + LodConfig.WORKLOAD.full * 2; }
	private int footprintHighWater() { return 32 * LodConfig.WORKLOAD.full; }

	private synchronized void tick() {
		try { tickWork(); } finally { publishDebug(); }
	}

	private void tickWork() {
		if (closed) return;
		long now = System.nanoTime(), deadline = now + LodConfig.WORKLOAD.tickBudgetNanos;
		Focus f = focus;
		Runtime runtime = Runtime.getRuntime();
		boolean running = active(f) && !level.getServer().isPaused() && !level.getServer().isStopped();
		if (!running) budget.idle();
		retained = heap.retained(now);
		budget.sample(now, f == null ? 16.7 : f.frameMs, tickMs(), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), retained,
			f != null && f.nearbyBusy, LodMemoryBudget.pressured());
		backgroundAllowed = running && budget.importsAllowed(now);
		advance(running, f, now, deadline);
		if (closed || !running) return;
		retiring.removeIf(p -> near(p, f) || level.getChunkSource().getChunkNow(p.x(), p.z()) == null);
		if (now >= nextStatus) {
			nextStatus = now + 30_000_000_000L;
			mcopt.metal.DiagnosticLog.printf(java.util.Locale.ROOT,
				"mcopt-lod: %s population %s, frame %.1fms tick %.1fms, heap %d MiB retained %d/%d MiB, loaded %d, FULL %d/%d probes %d/%d, imported %d generated %d%n",
				level.dimension().identifier(), budget.reason(), f.frameMs, tickMs(), (runtime.totalMemory()-runtime.freeMemory()) >> 20,
				retained < 0 ? -1 : retained >> 20, runtime.maxMemory() >> 20, level.getChunkSource().getLoadedChunksCount(),
				activeFull(), budget.fullLimit(now), activeProbes(), budget.probeLimit(now), imported, generated);
		}
		if (!captures.indexed() || congested.getAsBoolean()) return;
		for (int i = 0; i < LodConfig.WORKLOAD.imports && budget.importReady(now) && System.nanoTime() < deadline; i++) {
			Import item = imports.poll(); if (item == null) break;
			if (!within(item.key, f, importRadius) || captures.stamp(item.key) != item.stamp) continue;
			budget.imported();
			var live = level.getChunkSource().getChunkNow((int)(item.key >> 32), (int)item.key);
			if (live == null || live.isLightCorrect()) {
				imported++;
				accept.accept(live == null ? item.snapshot : LodSavedChunks.liveSnapshot(level, live, cavernY), item.stamp);
			} else { needsFull.add(item.key); saved.remove(item.key); }
			if (closed) return;
		}
		if (LodConfig.REFINE_RADIUS == 0 || level.getServer().getTickCount() < nextStart || retiring.size() >= retireLimit()) return;
		int loaded = level.getChunkSource().getLoadedChunksCount();
		if (!budget.ready(now) || loaded > LodRefineBudget.chunkLimit(f.rd)) return;
		if (poi == null && scoutRadius > 0) poi = new LodPoiPlan(level, scoutRadius);
		if (poi != null) poi.beginTick();
		// Each lane starts at most one request per tick, and is independently paced against wall-clock catch-up bursts.
		if (activeFull() < budget.fullLimit(now) && budget.generationReady(now, loaded, f.rd) && System.nanoTime() < deadline) scheduleFull(f, now, deadline);
		if (!closed && activeProbes() < budget.probeLimit(now) && budget.probeReady(now, loaded, f.rd) && System.nanoTime() < deadline) scheduleProbe(f, now, deadline);
	}

	private void advance(boolean running, Focus f, long now, long deadline) {
		int snapshots = 0;
		for (Request r : List.copyOf(requests.values())) {
			long key = key(r.pos);
			if (!running || !within(key, f, r.radius) || now - r.since > 30_000_000_000L) cancel(r);
			if (r.placeholder != null && level.getServer().getTickCount() > r.tick && !r.gate.cancelled()) {
				var destination = r.placeholder; r.placeholder = null;
				try {
					level.getChunkSource().getChunkFuture(r.pos.x(), r.pos.z(), net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS, false)
						.whenComplete((value, error) -> { if (error != null) destination.completeExceptionally(error); else destination.complete(value); });
				} catch (RuntimeException error) { destination.completeExceptionally(error); }
			}
			if (!r.gate.done()) continue;
			// Always retire cancellation/error work; successful captures respect the bounded per-tick server budget.
			if (!r.gate.cancelled() && !r.probe && (snapshots >= LodConfig.WORKLOAD.imports || System.nanoTime() >= deadline)) continue;
			boolean wait = false;
			try {
				Object result = r.gate.result();
				if (r.gate.cancelled()) { retry(r); continue; }
				if (!(result instanceof net.minecraft.server.level.ChunkResult<?> value) || value.orElse(null) == null)
					throw new IllegalStateException("Vanilla population request did not produce terrain");
				if (r.probe) {
					if (value.orElse(null) instanceof net.minecraft.world.level.chunk.ChunkAccess chunk) poi.confirmed(chunk);
				} else {
					var chunk = level.getChunkSource().getChunkNow(r.pos.x(), r.pos.z());
					if (chunk == null || !chunk.isLightCorrect()) { wait = true; continue; }
					if (congested.getAsBoolean()) { wait = true; continue; }
					snapshots++; generated++;
					accept.accept(LodSavedChunks.liveSnapshot(level, chunk, cavernY), r.stamp);
					needsFull.remove(key);
				}
			} catch (RuntimeException e) {
				retry(r); nextStart = level.getServer().getTickCount() + 20L;
				System.out.println("mcopt-lod: population deferred " + r.pos + ": " + e);
			} finally {
				if (!wait) {
					r.gate.finish(); requests.remove(key, r);
					if (!retiring.contains(r.pos)) retiring.add(r.pos);
				}
			}
			if (closed) return;
		}
	}

	private void scheduleFull(Focus f, long now, long deadline) {
		for (int i = 0; i < 256 && System.nanoTime() < deadline; i++) {
			boolean structure = false;
			LodPoiPlan.Work work = poi != null && fullStarts % 3 != 0 ? poi.nextFootprint(f.x, f.z, f.fx, f.fz, f.rd, requests::containsKey) : null;
			LodRefinePlan.Offset d = work == null ? plan.next(f.x, f.z, f.fx, f.fz, f.rd + 2) : null;
			if (work == null && d == null && poi != null) work = poi.nextFootprint(f.x, f.z, f.fx, f.fz, f.rd, requests::containsKey);
			if (work == null && d == null) return;
			ChunkPos pos = work == null ? new ChunkPos(f.x + d.x(), f.z + d.z()) : work.pos();
			structure = work != null;
			long key = key(pos);
			if (requests.containsKey(key)) {
				Request pending = requests.get(key);
				if (structure && !pending.probe) pending.scout = true;
				else if (structure) { poi.retry(pos, false); return; }
				continue;
			}
			if (!within(key, f, reachRadius) || near(pos, f) || !level.getWorldBorder().isWithinBounds(pos)) continue;
			if (!needsFull.contains(key) && (captures.has(key) || !structure && saved.contains(key))) continue;
			start(pos, false, structure, structure ? Math.min(reachRadius, scoutRadius + 16) : Math.min(reachRadius, LodConfig.REFINE_RADIUS + 4), now);
			fullStarts++; return;
		}
	}

	private void scheduleProbe(Focus f, long now, long deadline) {
		// Probing is cheaper than population: do not outrun the FULL lane and overflow confirmed footprints.
		if (poi == null || poi.pending() >= footprintHighWater()) return;
		for (int i = 0; i < 128 && System.nanoTime() < deadline; i++) {
			var work = poi.nextProbe(f.x, f.z, f.fx, f.fz, f.rd, requests::containsKey); if (work == null) return;
			if (requests.containsKey(key(work.pos()))) { poi.retry(work.pos(), true); return; }
			if (!level.getWorldBorder().isWithinBounds(work.pos())) continue;
			start(work.pos(), true, true, scoutRadius, now); return;
		}
	}

	private void start(ChunkPos pos, boolean probe, boolean scout, int radius, long now) {
		Request r = new Request(pos, probe, scout, radius, captures.stamp(key(pos)), now, level.getServer().getTickCount());
		var source = level.getChunkSource();
		try {
			if (probe) {
				source.addTicketWithRadius(ticket, pos, probeTicketRadius());
				r.placeholder = new CompletableFuture<>();
				r.gate.start(r.placeholder, () -> source.removeTicketWithRadius(ticket, pos, probeTicketRadius()));
			} else {
				var future = source.addTicketAndLoadWithRadius(ticket, pos, 0);
				r.gate.start(future.thenApply(result -> (Object)result), () -> source.removeTicketWithRadius(ticket, pos, 0));
			}
			requests.put(key(pos), r); budget.started(now, probe);
		} catch (RuntimeException e) {
			source.removeTicketWithRadius(ticket, pos, probe ? probeTicketRadius() : 0); retry(r);
			nextStart = level.getServer().getTickCount() + 20L;
			System.out.println("mcopt-lod: could not schedule population: " + e);
		}
	}
	private void retry(Request r) { if (!closed && r.scout && poi != null) poi.retry(r.pos, r.probe); }
	private static void cancel(Request r) {
		r.gate.cancel();
		if (r.placeholder != null) { r.placeholder.complete(null); r.placeholder = null; }
	}
	static int probeTicketRadius() {
		return net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)
			- net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
	}
	private double tickMs() {
		long[] samples = level.getServer().getTickTimesNanos(); long total = 0;
		for (long sample : samples) total += sample;
		return total / (samples.length * 1e6);
	}
	private void readSaved() {
		this.captures.awaitReady();
		long nextScan = 0;
		Focus indexed = null;
		while (!this.closed && !Thread.currentThread().isInterrupted()) {
			Focus f = this.focus;
			if (!this.backgroundAllowed || !active(f) || (indexed != null && Math.abs(f.x - indexed.x) < 32 && Math.abs(f.z - indexed.z) < 32 && System.nanoTime() < nextScan)) {
				LockSupport.parkNanos(250_000_000L);
				continue;
			}
			indexed = f;
			nextScan = System.nanoTime() + 30_000_000_000L;
			try {
				long[] keys = LodSavedIndex.scan(this.region, f.x, f.z, this.importRadius, f.fx, f.fz);
				Set<Long> known = ConcurrentHashMap.newKeySet();
				for (long key : keys) if (!this.needsFull.contains(key)) known.add(key);
				this.saved = known;
				this.needsFull.removeIf(key -> Math.abs((int)(key >> 32) - f.x) > this.importRadius || Math.abs((int)(long)key - f.z) > this.importRadius);
				var window = new LodSavedChunks.Window(this.level, this.cavernY);
				for (long key : keys) {
					if (this.closed || Thread.currentThread().isInterrupted()) return;
					Focus current = this.focus;
					if (!active(current) || Math.abs(current.x - f.x) > 32 || Math.abs(current.z - f.z) > 32) break;
					if (this.captures.has(key) && !this.needsFull.contains(key)) continue;
					while (!this.closed && (!this.backgroundAllowed || this.imports.remainingCapacity() == 0 || this.congested.getAsBoolean())) {
						LockSupport.parkNanos(50_000_000L);
						if (Thread.currentThread().isInterrupted()) return;
					}
					if (this.closed) return;
					if (!active(this.focus)) break;
					if (!within(key, this.focus, this.importRadius)) continue;
					if (this.captures.has(key) && !this.needsFull.contains(key)) continue; // a live capture may have arrived during backpressure
					long stamp = this.captures.stamp(key);
					LodChunks.Snapshot snapshot;
					try { snapshot = window.snapshot((int)(key >> 32), (int)key); }
					catch (java.io.IOException | RuntimeException e) { snapshot = null; }
					if (snapshot == null) {
						this.needsFull.add(key);
						this.saved.remove(key);
						continue;
					}
					this.imports.put(new Import(key, stamp, snapshot));
				}
			} catch (InterruptedException e) { Thread.currentThread().interrupt();return; }
			catch (java.io.IOException | RuntimeException e) { if (!this.closed) System.out.println("mcopt-lod: saved import deferred: " + e); }
		}
	}

	@Override public synchronized void close() {
		if (closed) return;
		closed = true; backgroundAllowed = false; ACTIVE.remove(level, this); reader.interrupt(); imports.clear();
		if (level.getServer().isSameThread()) cancelGeneration();
		else level.getServer().execute(() -> { synchronized (this) { cancelGeneration(); } });
		System.out.println("mcopt-lod: refinement stopped: " + imported + " saved imports, " + generated + " FULL chunks");
	}
	private void cancelGeneration() {
		for (Request r : requests.values()) { cancel(r); if (r.gate.done()) r.gate.finish(); }
		requests.clear();
	}
}
