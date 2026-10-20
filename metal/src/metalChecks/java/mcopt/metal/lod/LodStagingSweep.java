package mcopt.metal.lod;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import mcopt.metal.MetalBridge;

/** Same sixteen-chunk tile batch against either the current clipmap or an isolated pre-batching classpath. */
public final class LodStagingSweep {
	public static void main(String[] args) throws Exception {
		Method flush, retain, release;
		try { flush = LodClip.class.getDeclaredMethod("flushStaged"); flush.setAccessible(true); }
		catch (NoSuchMethodException legacy) { flush = null; }
		try { retain = LodClip.Snapshot.class.getDeclaredMethod("retain"); release = LodClip.Snapshot.class.getDeclaredMethod("release"); }
		catch (NoSuchMethodException legacy) { retain = null; release = null; }
		final Method retainSnapshot = retain, releaseSnapshot = release;
		var clip = new LodClip(MetalBridge.createHeadlessContext(), 256, 300, true);
		var last = new LodClip.Snapshot[1];
		int[] notifications = {0};
		try {
			clip.recenter(0, 0);
			clip.put(0, 0, 0, new int[4096], new int[4096], new int[4096], new int[LodClip.surfaceWords()], new int[4096], new int[8192]);
			clip.listener = new LodClip.Listener() {
				public void tilePut(int level, int tx, int tz, boolean refreshed) { }
				public void tileCleared(int level, int slot) { }
				public void tileStaged(LodClip.Snapshot snapshot) {
					try {
						if (releaseSnapshot != null && last[0] != null) releaseSnapshot.invoke(last[0]);
						if (retainSnapshot != null) retainSnapshot.invoke(snapshot);
					} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
					last[0] = snapshot; notifications[0]++;
				}
			};
			var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long thread = Thread.currentThread().threadId();
			long[] nanos = new long[200], bytes = new long[200];
			for (int pass = 0; pass < 240; pass++) {
				long before = allocations.getThreadAllocatedBytes(thread), start = System.nanoTime();
				for (int chunk = 0; chunk < 16; chunk++) {
					int bx = chunk % 4 * 16, bz = chunk / 4 * 16;
					for (int z = bz; z < bz + 16; z++) for (int x = bx; x < bx + 16; x++) {
						int i = z * 64 + x;
						clip.putCell(0, x, z, LodClip.geomWord(64 + pass % 16, false) | LodClip.plantBits(1), i + pass,
							i + 1, i + 2, i + 3, i + 4, i + 5, i + 6);
					}
					clip.refresh(0, 0, 0);
				}
				if (flush != null) flush.invoke(clip);
				if (last[0] == null || !clip.publish(last[0])) throw new AssertionError("Batch was not published");
				long elapsed = System.nanoTime() - start, allocated = allocations.getThreadAllocatedBytes(thread) - before;
				if (pass >= 40) { nanos[pass - 40] = elapsed; bytes[pass - 40] = allocated; }
				if (last[0].c[4095] != 4095 + pass) throw new AssertionError("Last edit missing from snapshot");
			}
			Arrays.sort(nanos);
			System.out.printf(java.util.Locale.ROOT,
				"STAGING mode=%s batches=%d chunksPerBatch=16 notificationsPerBatch=%.1f meanAllocatedBytes=%.1f meanMs=%.4f p50Ms=%.4f p95Ms=%.4f%n",
				flush == null ? "legacy" : "batched", bytes.length, notifications[0] / 240.0,
				Arrays.stream(bytes).average().orElseThrow(), Arrays.stream(nanos).average().orElseThrow() / 1e6, nanos[100] / 1e6, nanos[190] / 1e6);
		} finally { if (releaseSnapshot != null && last[0] != null) releaseSnapshot.invoke(last[0]); clip.free(); }
	}
}
