package mcopt.metal.lod;

import java.util.Arrays;
import mcopt.metal.MetalBridge;

/** Actual asynchronous mesh jobs, deferred results and GPU publication must release all array leases. */
public final class LodSnapshotMeshChecks {
	public static void main(String[] args) throws Exception {
		long ctx = MetalBridge.createHeadlessContext();
		var clip = new LodClip(ctx, 256, 300, true);
		var mesh = new LodMesh(ctx, clip);
		try {
			clip.recenter(0, 0);
			if (LodPublish.ON) clip.publisher = new LodPublish(ctx);
			int[] g = new int[4096], c = new int[4096], cr = new int[4096], runs = new int[4096];
			Arrays.fill(g, LodClip.geomWord(64, false));
			clip.put(0, 0, 0, g, c, cr, new int[LodClip.surfaceWords()], runs, new int[8192]);
			clip.put(0, 1, 0, g, c, cr, new int[LodClip.surfaceWords()], runs, new int[8192]);
			long frame = 0;
			for (int edit = 1; edit <= 400; edit++) {
				for (int tx = 0; tx < 2; tx++) {
					clip.putCell(0, tx * 64 + 2, 2, g[0], edit, 0, edit, 0, 0, 0, edit);
					clip.refresh(0, tx, 0);
				}
				step(ctx, clip, mesh, frame++);
				Thread.sleep(1);
			}
			long deadline = System.nanoTime() + 5_000_000_000L;
			while (clip.cellWords(2, 2)[1] != 400 || clip.cellWords(66, 2)[1] != 400) {
				if (System.nanoTime() > deadline) throw new AssertionError("Latest edits never reached published meshes");
				step(ctx, clip, mesh, frame++); Thread.sleep(1);
			}
			if (mesh.published.get() == 0 || clip.snapshotPool.reused == 0) throw new AssertionError("Real mesh/publication path did not exercise reuse");
			for (int x : new int[] {2, 66}) {
				int[] words = clip.cellWords(x, 2);
				if (words[4] != 400 || LodConfig.LIGHTING && words[7] != 400) throw new AssertionError("Latest material or light plane was lost");
			}
			// Shut down with native jobs/results possibly outstanding, as during a dimension switch.
			clip.putCell(0, 2, 2, g[0], 401, 0, 401, 0, 0, 0, 401); clip.refresh(0, 0, 0); clip.flushStaged();
		} finally {
			mesh.close(); clip.closeSnapshots();
			if (clip.snapshotPool.borrowedWords() != 0) throw new AssertionError("Mesh/map/CPU publication leaked snapshot leases: " + clip.snapshotPool.borrowedWords());
			for (long b : mesh.buffers()) LodNative.release(b);
			clip.free();
		}
		System.out.println("PASS: 800 edits through asynchronous native meshes preserve latest data, reuse storage and release all leases on world exit (" + System.getProperty("mcopt.lod.publish") + ")");
	}

	private static void step(long ctx, LodClip clip, LodMesh mesh, long frame) {
		if (clip.publisher != null) clip.publisher.begin(frame);
		clip.retireGpu(frame); mesh.integrate(frame);
		if (clip.publisher != null) {
			long enc = MetalBridge.createHeadlessEncoder(ctx);
			clip.publisher.encode(enc, clip); MetalBridge.commitAndWait(enc);
		}
	}
}
