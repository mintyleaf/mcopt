package mcopt.metal.lod;

import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import mcopt.metal.MetalBridge;
import sun.misc.Unsafe;

/** Real cache-path admission plus concurrent generation slots and backlog hysteresis. */
public final class LodSchedulingChecks {
	public static void main(String[] args) throws Exception {
		checkBudget(); checkCoverage(); checkStreaming(); checkLanes(); checkMemory(); checkCacheAdmission(); checkTelemetry(args);
		System.out.println("PASS: bounded generation, immediate completion/failure release, wakeups, cached-tile bypass, denied tiles without plane allocations and nearby backlog admission");
	}

	private static void checkBudget() throws Exception {
		var b = new LodGenerationBudget();
		for (int i = 0; i < 4; i++) check(b.acquire(true, false, 1, 0), "Healthy concurrency was reduced");
		for (int i = 0; i < 4; i++) {
			check(!b.acquire(true, true, 1, 0), "In-flight fallback jobs did not count when pressure arrived"); b.release(true);
		}
		check(b.acquire(true, true, 1, 0), "Fallback coverage stopped under pressure");
		check(!b.acquire(true, true, 1, 0) && !b.acquire(false, true, 1, 0), "Coarse/fine pressure limits ignored");
		long v = b.revision(); b.release(true);
		check(b.revision() != v && b.coarse() == 0, "Completion retained a slot");
		b.await(v); // A signal before entering wait must not be lost.
		for (int i = 0; i < 2; i++) check(b.acquire(false, true, 1, 2), "Explicit fine budget blocked");
		check(!b.acquire(false, true, 1, 2), "Explicit fine budget exceeded"); b.release(false); b.release(false);
		var pool = Executors.newFixedThreadPool(4);
		var running = new AtomicInteger(); var peak = new AtomicInteger();
		try {
			var tasks = new ArrayList<Future<?>>();
			for (int worker = 0; worker < 4; worker++) {
				boolean coarse = worker % 2 == 0;
				tasks.add(pool.submit(() -> {
				for (int i = 0; i < 500; i++) {
					try {
						while (true) {
							long observed = b.revision();
							if (b.acquire(coarse, true, 1, 1, 1)) break;
							b.await(observed);
						}
						try { peak.accumulateAndGet(running.incrementAndGet(), Math::max); Thread.yield(); }
						finally { running.decrementAndGet(); b.release(coarse); }
					} catch (InterruptedException e) { throw new AssertionError(e); }
				}
				}));
			}
			for (var task : tasks) task.get(10, TimeUnit.SECONDS);
		} finally { pool.shutdownNow(); }
		check(peak.get() == 1 && b.coarse() == 0 && b.fine() == 0, "Concurrent jobs exceeded cap or retained leases");
		LodYield.top = 2; LodYield.pressure = true;
		check(LodYield.admit(2), "Production fallback gate blocked first worker");
		try { throw new Expected(); } catch (Expected ignored) { } finally { LodYield.completed(); }
		check(LodYield.admit(2), "Failed job retained its production lease"); LodYield.completed();
		check(!LodYield.admit(0), "Production fine generation ignored missing chunks"); LodYield.completed();
	}

	private static void checkCoverage() {
		var p = new LodChunkPressure();
		check(!p.sample(1, 448, 0), "One edge chunk paused population");
		check(p.sample(56, 448, 0), "Substantial streaming backlog did not pause population");
		check(p.sample(30, 448, 0), "Backlog hysteresis oscillated");
		check(!p.sample(14, 448, 0), "Population did not recover after chunks caught up");
		check(p.sample(1, 448, 1), "Missing terrain next to the player was ignored");
		check(!p.sample(0, 448, 0), "Near-chunk recovery retained an arbitrary delay");
		long gib = 1L << 30, now = 1_000_000_000L;
		var budget = new LodRefineBudget(LodWorkload.BALANCED, 0);
		budget.sample(now, 16.7, 20, gib, 4 * gib, gib, true);
		check(!budget.ready(now) && budget.fullLimit(now) == 0 && budget.probeLimit(now) == 0 && budget.importsAllowed(now),
			"Nearby backlog did not prioritize real chunks while allowing bounded saved imports");
		budget.sample(now + 50_000_000L, 16.7, 20, gib, 4 * gib, gib, false);
		check(budget.ready(now + 50_000_000L), "Population recovery imposed a cooldown");
	}

	private static void checkStreaming() {
		var p = new LodStreamingPressure();
		check(!p.sample(120, 448, 0), "Ordinary elytra edge streaming blocked all detail");
		check(p.sample(150, 448, 0), "Severe backlog did not protect nearby generation");
		check(p.sample(130, 448, 0), "Severe backlog hysteresis oscillated");
		check(!p.sample(112, 448, 0), "Recovery retained a cooldown");
		check(p.sample(1, 448, 1), "Hole near the player admitted optional detail");
		check(!p.sample(0, 448, 0), "Nearby recovery retained a delay");
		LodYield.top = 3; LodYield.pressure = true; LodYield.severeChunkPressure = false;
		check(LodYield.admit(3), "Fallback lost its existing slot");
		check(!LodYield.available(0, true), "Streaming added a second competing worker"); LodYield.completed();
		check(LodYield.admit(0, true), "Visible detail cannot progress during ordinary streaming");
		check(!LodYield.available(3, false) && !LodYield.available(0, true), "Selection ignored shared in-flight allowance");
		LodYield.completed();
		check(!LodYield.admit(0, false), "Background detail borrowed the visible allowance");
		LodYield.severeChunkPressure = true;
		check(!LodYield.admit(0, true), "Near holes did not stop new detail");
		check(LodYield.admit(3), "Severe pressure stopped fallback coverage"); LodYield.completed();
		LodYield.severeChunkPressure = false;
		check(LodYield.admit(0, true), "Visible progress did not recover immediately"); LodYield.completed();
		var b = new LodGenerationBudget();
		check(b.acquire(false, false, 1, 1, 1) && b.acquire(false, false, 1, 1, 1), "Healthy generation was unnecessarily serialized");
		check(!b.acquire(true, true, 1, 1, 1), "In-flight detail ignored when pressure arrived"); b.release(false);
		check(!b.acquire(true, true, 1, 1, 1), "In-flight last job did not hold the shared slot"); b.release(false);
		check(b.acquire(true, true, 1, 1, 1), "Shared slot did not recover after completion"); b.release(true);
		check(LodField.streamingCandidate(true, 180, 260, 192, 31), "Visible tiles just ahead were excluded");
		check(!LodField.streamingCandidate(false, 180, 260, 192, 31)
			&& !LodField.streamingCandidate(true, 0, 100, 192, 31)
			&& !LodField.streamingCandidate(true, 1000, 1100, 192, 31), "Offscreen/covered/remote work took forward allowance");
		LodYield.pressure = false;
	}

	private record Queued(int priority, int lane, int id) implements Comparable<Queued> {
		public int compareTo(Queued q) { return Integer.compare(this.priority, q.priority); }
	}
	private static void checkLanes() throws Exception {
		var q = new LodJobQueue<Queued>(Queued::lane);
		q.add(new Queued(-100, LodJobQueue.OTHER, 1)); q.add(new Queued(-50, LodJobQueue.VISIBLE, 2));
		q.add(new Queued(0, LodJobQueue.COARSE, 3)); q.add(new Queued(20, LodJobQueue.CACHE, 4));
		check(q.poll(true, false, false).id == 3 && q.poll(true, false, false).id == 4,
			"Blocked fine heads hide eligible coarse/cache work");
		check(q.poll(false, false, false) == null && q.size() == 2, "Denied jobs were lost or selected");
		check(q.poll(false, true, false).id == 2 && q.poll(true, true, true).id == 1, "Eligibility or priority order changed");
		for (int i = 0; i < 4000; i++) q.add(new Queued(i, i % 4, i));
		var seen = ConcurrentHashMap.<Integer>newKeySet(); var pool = Executors.newFixedThreadPool(4);
		try {
			var workers = new ArrayList<Future<?>>();
			for (int i = 0; i < 4; i++) workers.add(pool.submit(() -> {
				while (!q.isEmpty()) { Queued j = q.poll(true, true, true); if (j != null) check(seen.add(j.id), "Duplicate concurrent dequeue"); }
			}));
			for (var w : workers) w.get(5, TimeUnit.SECONDS);
		} finally { pool.shutdownNow(); }
		check(seen.size() == 4000 && q.isEmpty(), "Concurrent four-head selection lost jobs");
	}

	private static void checkMemory() {
		long gib = 1L << 30;
		var memory = new LodMemoryBudget();
		check(!memory.sample(gib, 4 * gib), "Healthy heap was blocked");
		check(memory.sample(4 * gib * 89 / 100, 4 * gib), "Near-limit optional allocations continued");
		check(memory.sample(4 * gib * 82 / 100, 4 * gib), "Pressure oscillated before headroom recovered");
		check(!memory.sample(3 * gib, 4 * gib), "Heap recovery retained a timed cooldown");
		check(memory.sample(4 * gib + 1, 4 * gib), "Over-limit observation was ignored");
		check(!memory.sample(0, 0), "Unknown heap retained a stale stop");
		var budget = new LodRefineBudget(LodWorkload.BALANCED, 0);
		long now = 1_000_000_000L;
		budget.sample(now, 16.7, 20, 3 * gib, 4 * gib, gib, false, true);
		check(!budget.ready(now) && !budget.importsAllowed(now) && budget.fullLimit(now) == 0 && budget.probeLimit(now) == 0,
			"Population/imports ignored shared allocation headroom pressure");
		budget.sample(now + 50_000_000L, 16.7, 20, 3 * gib, 4 * gib, gib, false, false);
		check(budget.ready(now + 50_000_000L) && budget.importsAllowed(now + 50_000_000L), "Recovery added an arbitrary post-pressure delay");
	}

	@SuppressWarnings("unchecked")
	private static void checkCacheAdmission() throws Exception {
		var uf = Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
		var field = (LodField)((Unsafe)uf.get(null)).allocateInstance(LodField.class);
		Path root = Files.createTempDirectory("mcopt-scheduling-");
		var clip = new LodClip(MetalBridge.createHeadlessContext(), 256, 300);
		try (var captures = new LodCaptures(root, clip.levels, true)) {
			clip.recenter(0, 0); captures.awaitReady();
			set(field, "clip", clip); set(field, "cache", root); set(field, "captures", captures);
			set(field, "winTx", clip.winTx.clone()); set(field, "winTz", clip.winTz.clone());
			set(field, "pending", new ConcurrentHashMap<Long, Boolean>()); set(field, "results", new ConcurrentLinkedQueue<Runnable>());
			set(field, "chunkBuilt", ConcurrentHashMap.newKeySet()); set(field, "seq", new AtomicLong());
			for (String n : new String[] {"loaded", "generated", "genNanos", "staleJobsDropped"}) set(field, n, new AtomicLong());
			Class<?> jobType = Class.forName("mcopt.metal.lod.LodField$Job");
			var ctor = jobType.getDeclaredConstructor(double.class, long.class, long.class, Runnable.class); ctor.setAccessible(true);
			Method generate = LodField.class.getDeclaredMethod("generate", jobType); generate.setAccessible(true);
			long key = LodTile.key(0, 0, 0);
			Object missing = ctor.newInstance(0., 1L, key, null);
			LodYield.top = clip.levels - 1; LodYield.pressure = true;
			check(!(boolean)generate.invoke(field, missing), "Missing fine tile generated under pressure");
			var allocations = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
			long thread = Thread.currentThread().threadId(), before = allocations.getThreadAllocatedBytes(thread);
			for (int i = 0; i < 1000; i++) check(!(boolean)generate.invoke(field, missing), "Denied fine generation escaped its gate");
			long allocated = allocations.getThreadAllocatedBytes(thread) - before;
			check(allocated < 1_000_000, "Denied retries allocate full tile arrays: " + allocated);
			Method reprioritize = jobType.getDeclaredMethod("reprioritized", double.class, boolean.class); reprioritize.setAccessible(true);
			Object changed = reprioritize.invoke(missing, 7., true);
			Field miss = jobType.getDeclaredField("cacheMiss"); miss.setAccessible(true);
			check(miss.getBoolean(changed), "Reprioritization repeats known cache misses");
			int[] g = new int[4096], c = new int[4096], cr = new int[4096], tw = new int[LodClip.surfaceWords()], runs = new int[4096], pl = new int[8192];
			Arrays.fill(g, LodClip.geomWord(71, false)); Arrays.fill(c, 0x12345678);
			Method save = LodField.class.getDeclaredMethod("save", long.class, int[].class, int[].class, int[].class, int[].class, int[].class, int[].class); save.setAccessible(true);
			save.invoke(field, key, g, c, cr, tw, runs, pl);
			Object cached = ctor.newInstance(0., 2L, key, null);
			Method memoryGate = LodField.class.getDeclaredMethod("generate", jobType, boolean.class); memoryGate.setAccessible(true);
			before = allocations.getThreadAllocatedBytes(thread);
			for (int i = 0; i < 1000; i++) check(!(boolean) memoryGate.invoke(field, cached, true), "Cache decoded planes during heap pressure");
			long deniedBytes = allocations.getThreadAllocatedBytes(thread) - before;
			check(deniedBytes < 1_000_000, "Memory-denied cache jobs allocate planes: " + deniedBytes);
			check((boolean)generate.invoke(field, cached), "Valid fine cache hit was blocked by CPU generation admission");
			Field loaded = LodField.class.getDeclaredField("loaded"); loaded.setAccessible(true);
			check(((AtomicLong)loaded.get(field)).get() == 1, "Cache bypass regenerated instead of loading");
			LodYield.completed();
			checkMaintenance(field, ctor, jobType);
			checkReorder(field, ctor, jobType, clip);
			System.out.printf("PASS: real fine-tile cache loads during pressure; 1,000 denied retries allocated %,d bytes%n", allocated);
		} finally {
			LodYield.completed(); LodYield.pressure = false; clip.free();
			try (var paths = Files.walk(root)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
		}
	}

	private static void checkMaintenance(LodField field, Constructor<?> ctor, Class<?> jobType) throws Exception {
		Method lane = LodField.class.getDeclaredMethod("jobLane", jobType); lane.setAccessible(true);
		@SuppressWarnings({"rawtypes", "unchecked"}) var queue = new LodJobQueue(j -> {
			try { return (int)lane.invoke(field, j); } catch (Exception e) { throw new AssertionError(e); }
		});
		set(field, "jobs", queue); set(field, "maintenance", new PriorityBlockingQueue<>());
		Method enqueue = LodField.class.getDeclaredMethod("enqueue", jobType), work = LodField.class.getDeclaredMethod("work");
		enqueue.setAccessible(true); work.setAccessible(true);
		// A higher-priority fine tile is blocked by missing chunks; a correction must still run immediately.
		enqueue.invoke(field, ctor.newInstance(-100., 3L, LodTile.key(0, 1, 0), null));
		var completed = new CompletableFuture<Void>();
		var failure = new AtomicReference<Throwable>();
		Thread worker = new Thread(() -> { try { work.invoke(field); } catch (Throwable e) { failure.set(e); } }, "lod-scheduling-worker-check");
		worker.start();
		try {
			long deadline = System.nanoTime() + 2_000_000_000L;
			while (LodYield.pausedNow() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
			check(LodYield.pausedNow() > 0, "Fixture worker never reached blocked generation admission");
			// A cached tile behind the denied fine head must load without a pressure transition or a retry timer.
			long loaded = field.loaded.get();
			enqueue.invoke(field, ctor.newInstance(100., 5L, LodTile.key(0, 0, 0), null));
			long cacheDeadline = System.nanoTime() + 2_000_000_000L;
			while (field.loaded.get() == loaded && System.nanoTime() < cacheDeadline) Thread.sleep(1);
			check(field.loaded.get() > loaded, "Real worker's denied fine head blocked a valid cached tile");
			enqueue.invoke(field, ctor.newInstance(0., 4L, 0L, (Runnable)() -> completed.complete(null)));
			completed.get(2, TimeUnit.SECONDS);
		} finally {
			worker.interrupt(); worker.join(2000);
			check(!worker.isAlive(), "Interrupted blocked worker failed to stop");
		}
		if (failure.get() != null) throw new AssertionError("Worker failed", failure.get());
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void checkReorder(LodField field, Constructor<?> ctor, Class<?> jobType, LodClip clip) throws Exception {
		Field f = LodField.class.getDeclaredField("jobs"); f.setAccessible(true); var queue = (LodJobQueue)f.get(field);
		var drained = new ArrayList<>(); queue.drainTo(drained);
		long stale = LodTile.key(0, clip.winTx[0] - 1, clip.winTz[0]);
		var pendingField = LodField.class.getDeclaredField("pending"); pendingField.setAccessible(true);
		var pending = (ConcurrentHashMap<Long, Boolean>)pendingField.get(field);
		pending.put(stale, true); queue.add((Comparable)ctor.newInstance(-100., 10L, stale, null));
		long valid = LodTile.key(0, clip.winTx[0], clip.winTz[0]); pending.put(valid, true);
		queue.add((Comparable)ctor.newInstance(0., 11L, valid, null));
		Method reorder = LodField.class.getDeclaredMethod("reorder", Map.class, int[].class, int[].class); reorder.setAccessible(true);
		var rankType = Class.forName("mcopt.metal.lod.LodField$Rank");
		var rank = rankType.getDeclaredConstructor(double.class, boolean.class); rank.setAccessible(true);
		reorder.invoke(field, Map.of(valid, rank.newInstance(0., false), stale, rank.newInstance(-100., true)), clip.winTx.clone(), clip.winTz.clone());
		check(queue.size() == 1 && !pending.containsKey(stale) && pending.containsKey(valid) && field.staleJobsDropped.get() == 1,
			"Moving windows retained abandoned jobs or discarded valid coordinates");
		queue.drainTo(new ArrayList<>()); pending.clear(); set(field, "firstSeen", new HashMap<Long, Long>());
		field.rdBlocks = 64;
		Method scan = LodField.class.getDeclaredMethod("scanScreen", double.class, double.class, double.class, double.class,
			double.class, org.joml.Matrix4f.class, int[].class, int[].class, LodSeam.ChunkMask.class); scan.setAccessible(true);
		scan.invoke(field, 0., 0., 100., 31., 0., new org.joml.Matrix4f().ortho(-512, 512, -512, 512, -512, 512),
			clip.winTx.clone(), clip.winTz.clone(), (LodSeam.ChunkMask)(x, z) -> false);
		var scanned = new ArrayList<Comparable>(); queue.drainTo(scanned);
		Field cacheMiss = jobType.getDeclaredField("cacheMiss"); cacheMiss.setAccessible(true);
		Field key = jobType.getDeclaredField("key"); key.setAccessible(true);
		var turned = new HashMap<Long, Object>();
		// Simulate completed cache checks, keeping the actual scanner's priority and forward eligibility.
		for (var job : scanned) {
			cacheMiss.setBoolean(job, true); queue.add(job); turned.put(key.getLong(job), rank.newInstance(0., false));
		}
		check(queue.size(LodJobQueue.VISIBLE) > 0, "Real screen scan lost new tiles' forward eligibility during reorder");
		reorder.invoke(field, turned, clip.winTx.clone(), clip.winTz.clone());
		check(queue.size(LodJobQueue.VISIBLE) == 0 && queue.poll(false, true, false) == null,
			"Unrequested tiles retained a forward allowance after reranking");
		reorder.invoke(field, Map.of(), clip.winTx.clone(), clip.winTz.clone());
		check(queue.isEmpty() && pending.isEmpty(), "Unrequested ring jobs remained pending inside the larger clipmap windows");
	}

	private static void checkTelemetry(String[] args) throws Exception {
		Path path = args.length > 0 ? Path.of(args[0]) : Files.createTempFile("mcopt-scheduling-", ".jfr");
		var b = new LodGenerationBudget(); b.acquire(true, false, 1, 0);
		var dimension = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
			net.minecraft.resources.Identifier.withDefaultNamespace("overworld"));
		try (var r = new jdk.jfr.Recording()) {
			r.enable(LodScheduling.Sample.class); r.start();
			long now = System.nanoTime();
			LodScheduling.sample(now, dimension, new LodGenStats.Coverage(90, 448, 2), b, true, true, 3, null, null, 80, 120, -48);
			LodScheduling.sample(now + 1, dimension, new LodGenStats.Coverage(90, 448, 2), b, true, true, 3, null, null, 80, 120, -48);
			r.stop(); r.dump(path);
		} finally { b.release(true); }
		var events = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream().filter(e -> e.getEventType().getName().equals("mcopt.LodScheduling")).toList();
		check(events.size() == 1 && events.getFirst().getInt("missingChunks") == 90 && events.getFirst().getInt("coarseRunning") == 1
			&& events.getFirst().getBoolean("populationPressure") && events.getFirst().getLong("heapMaxBytes") > 0
			&& events.getFirst().getLong("heapUsedBytes") > 0 && events.getFirst().hasField("memoryPressure"),
			"JFR coverage/cap/headroom fields or once-per-second throttling failed");
		check(events.getFirst().getInt("meshCompileQueued") == -1 && events.getFirst().getInt("lodTilesMissing") == -1
			&& events.getFirst().getInt("lodResultsReady") == -1 && events.getFirst().getLong("lodTilesLoaded") == -1
			&& events.getFirst().getDouble("cameraY") == 120,
			"Unavailable readiness fields masquerade as zero backlog or camera sample was lost");
		if (args.length == 0) Files.delete(path);
	}

	private static void set(Object owner, String name, Object value) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
	private static final class Expected extends RuntimeException { }
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
