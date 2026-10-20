package mcopt.metal.lod;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

/** Exercises real compressed capture files with eviction, negative coordinates and concurrent readers. */
public final class LodCaptureIoChecks {
	private static final Method GET;
	static {
		try { GET = LodCaptures.class.getDeclaredMethod("get", long.class); GET.setAccessible(true); }
		catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
	}
	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		Path root = Files.createTempDirectory("mcopt-capture-io-");
		try {
			for (int level = 0; level <= 3; level++) checkTile(root.resolve("level" + level), level);
			checkConcurrent(root.resolve("concurrent"));
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
			}
		}
	}
	private static void checkTile(Path root, int level) throws Exception {
		int chunks = 4 << level;
		try (var store = new LodCaptures(root, 8, true, 1)) {
			for (int z = -chunks; z < 0; z++) for (int x = -chunks; x < 0; x++) {
				int[][] p = new int[LodCaptures.PLANES][256];
				for (int i = 0; i < 256; i++) {
					p[LodCaptures.GEOM][i] = LodClip.geomWord(70 + (i & 7), false);
					p[LodCaptures.COLOR][i] = 0x89ABCDEF ^ (x * 71 + z * 991 + i);
					p[LodCaptures.MATERIAL][i] = LodClip.TEX_LIGHT;
					p[LodCaptures.LIGHT][i] = 0xF0E1D2C3;
					p[LodCaptures.PLANT_B][i] = i * 37;
				}
				store.put(new LodCaptures.Capture(x, z, p));
			}
		}
		int[][] expected = destination(), actual = destination();
		long referenceReads, groupedReads, before;
		var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId(), referenceBytes, groupedBytes;
		try (var store = new LodCaptures(root, 8, true, 1)) {
			store.awaitReady(); before = allocations.getThreadAllocatedBytes(thread);
			reference(store, level, expected);
			referenceBytes = allocations.getThreadAllocatedBytes(thread) - before;
			referenceReads = store.diskReadCount();
		}
		try (var store = new LodCaptures(root, 8, true, 1)) {
			store.awaitReady(); before = allocations.getThreadAllocatedBytes(thread);
			store.overlay(LodTile.key(level, -1, -1), actual[0], actual[1], actual[2], actual[3], actual[4], actual[5]);
			groupedBytes = allocations.getThreadAllocatedBytes(thread) - before;
			groupedReads = store.diskReadCount();
			var field = LodCaptures.class.getDeclaredField("memory"); field.setAccessible(true);
			if (((java.util.Map<?, ?>) field.get(store)).size() > 1) throw new AssertionError("Cache limit changed");
		}
		for (int plane = 0; plane < expected.length; plane++)
			if (!Arrays.equals(expected[plane], actual[plane])) throw new AssertionError("Overlay mismatch level " + level + " plane " + plane);
		if (groupedReads != chunks * chunks || referenceReads != groupedReads * (16 >> level))
			throw new AssertionError("Unexpected read count " + referenceReads + " -> " + groupedReads);
		System.out.printf("PASS level%d: identical six output planes, disk decodes %d -> %d; allocated %.1f -> %.1f KiB; one-entry resident cache%n",
			level, referenceReads, groupedReads, referenceBytes / 1024.0, groupedBytes / 1024.0);
	}
	private static void checkConcurrent(Path root) throws Exception {
		try (var store = new LodCaptures(root, 5, true, 1)) { store.put(new LodCaptures.Capture(0, 0, new int[LodCaptures.PLANES][256])); }
		try (var store = new LodCaptures(root, 5, true, 1); var threads = Executors.newFixedThreadPool(8)) {
			store.awaitReady(); var start = new CyclicBarrier(8);
			var results = new java.util.ArrayList<java.util.concurrent.Future<LodCaptures.Capture>>();
			for (int i = 0; i < 8; i++) results.add(threads.submit(() -> { start.await(); return get(store, 0); }));
			LodCaptures.Capture first = results.getFirst().get();
			for (var result : results) if (result.get() != first) throw new AssertionError("Concurrent readers decoded different copies");
			if (store.diskReadCount() != 1) throw new AssertionError("Duplicate simultaneous disk decode");
		}
		System.out.println("PASS: eight simultaneous readers share one decoded capture");
	}
	private static LodCaptures.Capture get(LodCaptures store, long key) throws Exception { return (LodCaptures.Capture) GET.invoke(store, key); }
	private static int[][] destination() {
		int n = LodTile.CELLS;
		return new int[][] {new int[n], new int[n], new int[n], new int[2*n], new int[n], new int[2*n]};
	}
	/** Prior row traversal is an independent reference for cell indexing and all material/light/plant planes. */
	private static void reference(LodCaptures store, int level, int[][] out) throws Exception {
		int step = 1 << level, origin = -(64 << level), shift = Math.min(level, 4), size = 16 >> shift;
		long last = Long.MIN_VALUE; LodCaptures.Capture source = null;
		for (int i = 0; i < LodTile.CELLS; i++) {
			int x = origin + (i & 63) * step, z = origin + (i >> 6) * step;
			long key = LodForest.chunkKey(x >> 4, z >> 4);
			if (key != last) { source = get(store, key); last = key; }
			if (source == null) continue;
			int k = ((z & 15) >> shift) * size + ((x & 15) >> shift); int[][] p = source.atLevel(level);
			out[0][i] = level == 0 ? p[LodCaptures.GEOM][k] : LodClip.coarseGeom(p[LodCaptures.GEOM][k]);
			out[1][i] = p[LodCaptures.COLOR][k]; out[2][i] = p[LodCaptures.CROWN][k];
			out[3][i] = p[LodCaptures.MATERIAL][k]; out[3][LodTile.CELLS+i] = p[LodCaptures.LIGHT][k];
			out[4][i] = p[LodCaptures.RUNS][k]; out[5][i] = p[LodCaptures.PLANT_A][k]; out[5][LodTile.CELLS+i] = p[LodCaptures.PLANT_B][k];
		}
	}
}
