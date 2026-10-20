package mcopt.metal.lod;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Back-pressure for the far terrain's generation (-Dmcopt.lod.yield=server|missing[:N], default off): the game's own chunks
 * first. Measured: with far-terrain generation on, a 60 b/s flight leaves twice as many of the render distance's chunks
 * missing as with it off (36-40% vs 19% on the mini), drawing or not; the far terrain's workers take the cores the server's
 * chunk generation needs. With this on, a worker about to generate a tile (and at each stage of it) waits while the server
 * has chunk work near the player:
 * - server: the integrated server's worldgen dispatcher has queued chunk tasks (ChunkMap.worldgenTaskDispatcher.hasWork());
 * - missing[:N]: the client lacks more than N chunks within the render distance (what the real-chunk gate measures).
 * - spare[:M]: under pressure (the client lacks chunks in the render distance), a fine tile may still run while fewer far
 *   workers generate than the cores the server's worldgen pool leaves idle (cores - its active threads - M, M default 2: the
 *   render and server threads), measured at admission from Util.backgroundExecutor()'s ForkJoinPool.
 * -Dmcopt.lod.yield.budget=K explicitly allows K fine workers under pressure (default 0). In missing/spare modes,
 * mcopt.lod.yield.streaming=true also lets visible tiles ahead share the fallback allowance during ordinary edge streaming.
 * Near-player holes or severe backlog stop this automatic fine allowance; population has its own stricter backlog gate.
 * The default shared allowance is one job total, not one fallback plus one fine job. Cached tiles and real summaries bypass CPU generation
 * admission; optional cached tile decoding still yields to LodMemoryBudget. Pressure is sampled on the render thread every3frames.
 */
final class LodYield {
	private LodYield() {
	}

	static final String SPEC = System.getProperty("mcopt.lod.yield", "");
	static final int MODE = SPEC.startsWith("server") ? 1 : SPEC.startsWith("missing") ? 2 : SPEC.startsWith("spare") ? 3 : 0;
	static final boolean ON = MODE != 0;
	static final int THRESH = SPEC.contains(":") ? Integer.parseInt(SPEC.substring(SPEC.indexOf(':') + 1).trim()) : MODE == 3 ? 2 : 0;
	private static final int CORES = Runtime.getRuntime().availableProcessors();
	private static final LodGenerationBudget generation = new LodGenerationBudget();
	private static java.util.concurrent.@Nullable ForkJoinPool pool;
	static final int BUDGET = Integer.getInteger("mcopt.lod.yield.budget", 0);
	private static final int COARSE_BUDGET = Math.max(1, Integer.getInteger("mcopt.lod.yield.coarseBudget", 1));
	private static final boolean STREAMING = Boolean.parseBoolean(System.getProperty("mcopt.lod.yield.streaming", "true"));

	static volatile boolean pressure;
	static volatile boolean populationPressure;
	static volatile boolean severeChunkPressure;
	static final AtomicLong streamingStarts = new AtomicLong();
	private static boolean memoryPressure;
	private static java.lang.ref.WeakReference<ClientLevel> coverageOwner = new java.lang.ref.WeakReference<>(null);
	private static LodChunkPressure chunkPressure = new LodChunkPressure();
	private static LodStreamingPressure streamingPressure = new LodStreamingPressure();
	/** The coarsest fallback level, set by Lod with the clipmap. */
	static volatile int top = Integer.MAX_VALUE;
	private static final AtomicInteger holders = new AtomicInteger(), paused = new AtomicInteger();
	static final AtomicLong pausedNanos = new AtomicLong(), pressureFrames = new AtomicLong(), sampledFrames = new AtomicLong();
	private static final ThreadLocal<int[]> STATE = ThreadLocal.withInitial(() -> new int[2]);   // nesting depth, holds a budget slot
	private static long frames;
	private static @Nullable Object dispatcher;
	private static @Nullable Object dispatcherOwner;
	private static @Nullable Field dispatcherField;
	private static java.lang.reflect.Method hasWork;

	/** Render thread: the pressure now. */
	static void frame(Minecraft mc, double camX, double camY, double camZ, int rd, LodField field) {
		if (mc.level == null) return;
		if (coverageOwner.get() != mc.level) {
			coverageOwner = new java.lang.ref.WeakReference<>(mc.level);
			chunkPressure = new LodChunkPressure();
			streamingPressure = new LodStreamingPressure();
			frames = 0;
		}
		if (frames++ % 3 != 0) return;
		var coverage = LodGenStats.coverage(mc.level, camX, camZ, rd);
		boolean backlog = chunkPressure.sample(coverage.missing(), coverage.total(), coverage.nearMissing());
		boolean severe = streamingPressure.sample(coverage.missing(), coverage.total(), coverage.nearMissing());
		boolean p;
		if (MODE == 1) {
			p = serverHasWork(mc);
		} else {
			p = coverage.missing() > (MODE == 3 ? 0 : THRESH);
		}
		boolean memory = LodMemoryBudget.pressured();
		boolean changed = pressure != p || populationPressure != backlog || memoryPressure != memory || severeChunkPressure != severe;
		memoryPressure = memory;
		pressure = p;
		populationPressure = backlog;
		severeChunkPressure = severe;
		if (changed) wake();
		LodScheduling.sample(System.nanoTime(), mc.level.dimension(), coverage, generation, p, backlog, paused.get(),
			mc, field, camX, camY, camZ);
		sampledFrames.incrementAndGet();
		if (p) pressureFrames.incrementAndGet();
	}

	private static boolean serverHasWork(Minecraft mc) {
		try {
			MinecraftServer server = mc.getSingleplayerServer();
			if (server == null || mc.level == null) return false;
			ServerLevel sl = server.getLevel(mc.level.dimension());
			if (sl == null) return false;
			ChunkMap map = sl.getChunkSource().chunkMap;
			if (dispatcherOwner != map) {
				if (dispatcherField == null) {
					dispatcherField = ChunkMap.class.getDeclaredField("worldgenTaskDispatcher");
					dispatcherField.setAccessible(true);
				}
				dispatcher = dispatcherField.get(map);
				hasWork = dispatcher.getClass().getMethod("hasWork");
				dispatcherOwner = map;
			}
			return (boolean) hasWork.invoke(dispatcher);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * -Dmcopt.lod.yield.at=take (default): the gate is LodField's worker loop, before a generation job is taken on (admit);
 * a job that may not run goes back into an eligibility lane. Cached, fallback and visible jobs remain selectable behind
 * a denied fine head. tile: the gate is inside LodNoise.generate (enter, checkpoints), the first
	 * version: a worker blocks holding its tile (holes when all of them do).
	 */
	static final boolean AT_TAKE = !"tile".equals(System.getProperty("mcopt.lod.yield.at", "take"));

	/** Per worker: coarse (1) or fine (2) lease, released in the job's finally block, including failures/shutdown. */
	private static final ThreadLocal<int[]> SLOT = ThreadLocal.withInitial(() -> new int[1]);

	/**
	 * LodField's worker, about to generate a tile of this level: whether it may now (else the job goes back into the queue).
	 * All running jobs count, including ones admitted before pressure arrived. Cache loads do not need a lease.
	 */
	static boolean admit(int level) {
		return admit(level, false);
	}

	static boolean streamingAllowed() { return STREAMING && (MODE == 2 || MODE == 3) && !severeChunkPressure; }

	private static int fineBudget() {
		return Math.max(0, BUDGET) + (MODE == 3 ? Math.max(0, CORES - serverActive() - THRESH) : 0);
	}

	/** Non-reserving hint for queue selection. The acquire below resolves competing workers atomically. */
	static boolean available(int level, boolean visible) {
		if (!ON || !AT_TAKE) return true;
		int base = fineBudget();
		int fine = Math.max(base, visible && streamingAllowed() ? 1 : 0);
		return generation.available(level >= top, pressure, COARSE_BUDGET, fine, COARSE_BUDGET + base);
	}

	static boolean admit(int level, boolean visible) {
		if (!AT_TAKE) return true;
		int[] slot = SLOT.get();
		if (slot[0] != 0) throw new IllegalStateException("LOD generation lease still held");
		boolean coarse = level >= top;
		int base = fineBudget();
		int fineLimit = Math.max(base, visible && streamingAllowed() ? 1 : 0);
		// Fine streaming shares the existing fallback allowance, rather than adding another worker to it.
		if (!generation.acquire(coarse, pressure, COARSE_BUDGET, fineLimit, COARSE_BUDGET + base)) return false;
		if (!coarse && pressure && visible && base == 0) streamingStarts.incrementAndGet();
		slot[0] = coarse ? 1 : 2;
		return true;
	}

	static void completed() {
		if (!AT_TAKE) return;
		int[] slot = SLOT.get();
		int kind = slot[0]; slot[0] = 0;
		if (kind != 0) generation.release(kind == 1);
	}

	static long revision() { return generation.revision(); }
	static void wake() { generation.signal(); }
	static void awaitWork(long observed) {
		try { generation.awaitWork(observed); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); }
	}

	/** The server's worldgen pool (Worker-Main): threads busy now (approximate, any thread may ask). */
	private static int serverActive() {
		try {
			java.util.concurrent.ForkJoinPool p = pool;
			if (p == null) {
				if (!(net.minecraft.util.Util.backgroundExecutor().service() instanceof java.util.concurrent.ForkJoinPool f)) return CORES;
				pool = p = f;
			}
			return p.getActiveThreadCount();
		} catch (RuntimeException e) {
			return CORES;
		}
	}

	/** Wait for coverage/work/permit changes instead of repeatedly locking the queue every 3 ms. */
	static void idle(long observed) {
		long t0 = System.nanoTime();
		paused.incrementAndGet();
		try {
			generation.await(observed);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			paused.decrementAndGet();
			pausedNanos.addAndGet(System.nanoTime() - t0);
		}
	}

	/** A worker starts generating a tile of the given level (nested calls on the same thread pass through). */
	static void enter(int level, int top) {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (s[0]++ > 0 || level >= top) return;
		await(s);
	}

	/** Between a tile's stages: wait if pressure came up and this worker holds no budget slot. */
	static void checkpoint() {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (s[0] == 1 && s[1] == 0 && pressure) await(s);
	}

	static void exit() {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (--s[0] == 0 && s[1] != 0) {
			s[1] = 0;
			holders.decrementAndGet();
		}
	}

	private static void await(int[] s) {
		long t0 = 0;
		while (pressure) {
			int h = holders.get();
			if (h < BUDGET && holders.compareAndSet(h, h + 1)) {
				s[1] = 1;
				break;
			}
			if (t0 == 0) {
				t0 = System.nanoTime();
				paused.incrementAndGet();
			}
			try {
				Thread.sleep(3);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (t0 != 0) {
			paused.decrementAndGet();
			pausedNanos.addAndGet(System.nanoTime() - t0);
		}
	}

	static int pausedNow() {
		return paused.get();
	}

	static String summary() {
		long n = Math.max(1, sampledFrames.get());
		return String.format(Locale.ROOT, "pressure %.0f%% of samples, %.1f worker-s paused", 100.0 * pressureFrames.get() / n, pausedNanos.get() / 1e9);
	}
}
