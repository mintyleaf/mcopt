package mcopt.metal.lod;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryUtil;

/** Real clipmap staging: coalescing must keep worker inputs immutable and reject recycled tile slots. */
public final class LodStagingChecks {
	public static void main(String[] args) {
		if (!LodClip.PAIRS) throw new AssertionError("Run with publish=pair");
		long ctx = MetalBridge.createHeadlessContext();
		var clip = new LodClip(ctx, 256, 300, true);
		if (LodPublish.ON) { clip.publisher = new LodPublish(ctx); clip.publisher.begin(0); }
		var snapshots = new ArrayList<LodClip.Snapshot>();
		try {
			clip.recenter(0, 0);
			clip.put(0, 0, 0, new int[4096], new int[4096], new int[4096], new int[LodClip.surfaceWords()], new int[4096], new int[8192]);
			clip.listener = new LodClip.Listener() {
				public void tilePut(int level, int tx, int tz, boolean refreshed) { }
				public void tileCleared(int level, int slot) { }
				public void tileStaged(LodClip.Snapshot snapshot) { snapshots.add(snapshot.retain()); }
			};
			var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long thread = Thread.currentThread().threadId(), before = allocations.getThreadAllocatedBytes(thread);
			for (int chunk = 0; chunk < 16; chunk++) {
				int bx = chunk % 4 * 16, bz = chunk / 4 * 16;
				for (int z = bz; z < bz + 16; z++) for (int x = bx; x < bx + 16; x++) {
					int i = z * 64 + x;
					clip.putCell(0, x, z, LodClip.geomWord(70, false) | LodClip.plantBits(1), i, i + 1, i + 2, i + 3, i + 4, i + 5, i + 6);
				}
				clip.refresh(0, 0, 0);
			}
			if (!snapshots.isEmpty()) throw new AssertionError("Mesh work escaped frame coalescing");
			clip.flushStaged();
			long bytes = allocations.getThreadAllocatedBytes(thread) - before;
			if (snapshots.size() != 1) throw new AssertionError("Sixteen chunks did not coalesce to one tile snapshot");
			LodClip.Snapshot first = snapshots.getFirst();
			for (int i = 0; i < 4096; i++) {
				if (first.c[i] != i || first.cr[i] != i + 1 || first.tw[i] != i + 2 || first.runs[i] != i + 3
					|| first.pl[i] != i + 4 || first.pl[4096 + i] != i + 5 || first.tw[4096 + i] != i + 6)
					throw new AssertionError("Coalescing lost geometry, materials, plants or lighting");
			}
			int[] frozen = first.c.clone();
			long version = first.version;
			clip.putCell(0, 0, 0, LodClip.geomWord(80, false), 999, 0, 0, 0, 0, 0, 0);
			clip.refresh(0, 0, 0);
			clip.flushStaged();
			LodClip.Snapshot second = snapshots.getLast();
			if (!Arrays.equals(first.c, frozen) || first.version != version || second == first || second.c[0] != 999)
				throw new AssertionError("An edit mutated the worker's in-flight snapshot");
			if (!clip.publish(first) || !clip.hasStaged(0, 0, 0) || !clip.publish(second) || clip.hasStaged(0, 0, 0))
				throw new AssertionError("Publishing an old result erased a newer edit");
			clip.putCell(0, 1, 0, LodClip.geomWord(90, false), 111, 0, 0, 0, 0, 0, 0);
			clip.refresh(0, 0, 0);
			clip.flushStaged();
			LodClip.Snapshot third = snapshots.getLast();
			if (third.version <= second.version) throw new AssertionError("Post-publication version regressed; mesh would reject the edit");
			if (!clip.publish(third)) throw new AssertionError("Latest snapshot was not publishable");
			if (clip.publisher != null) {
				long enc = MetalBridge.createHeadlessEncoder(ctx);
				clip.publisher.encode(enc, clip);
				MetalBridge.commitAndWait(enc);
				long lastLight = clip.tex + clip.levelWords * 12 + (63L * clip.n + 63) * 4;
				if (MemoryUtil.memGetInt(clip.color + 4) != 111 || MemoryUtil.memGetInt(lastLight) != 4101)
					throw new AssertionError("GPU publication lost the latest color or untouched light plane: color=" + MemoryUtil.memGetInt(clip.color + 4)
						+ " light=" + MemoryUtil.memGetInt(lastLight));
			}
			clip.putCell(0, 2, 0, LodClip.geomWord(95, false), 222, 0, 0, 0, 0, 0, 0);
			clip.refresh(0, 0, 0);
			clip.recenter(10000, 10000);
			int count = snapshots.size();
			clip.flushStaged();
			if (snapshots.size() != count || clip.publish(third)) throw new AssertionError("Recycled slots retained a staged edit");
			System.out.printf("PASS: 16 chunk edits coalesce to one immutable tile; all planes, late results, repeated edits and slot recycling preserved; batch allocation %,d bytes%n", bytes);
		} finally {
			for (var snapshot : snapshots) snapshot.release();
			clip.free();
		}
	}
}
