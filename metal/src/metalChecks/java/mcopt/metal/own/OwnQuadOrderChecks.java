package mcopt.metal.own;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

/** Compare all metadata/quad/record bytes against the original key permutation through capacity growth and reuse. */
public final class OwnQuadOrderChecks {
	private static final Method SLOT, KEY;
	static {
		try {
			SLOT = OwnQuads.Layer.class.getDeclaredMethod("slot", int.class); SLOT.setAccessible(true);
			KEY = OwnQuads.class.getDeclaredMethod("key", int.class, float.class, int.class, int.class); KEY.setAccessible(true);
		} catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
	}
	private static int stamp(int bucket, int index, int iteration) { return bucket << 24 | iteration << 16 | index; }
	private static long[][] fill(OwnQuads.Layer layer, int n, int iteration) throws Exception {
		layer.reset();
		long[][] expected = new long[7][];
		for (int b = 0; b < 7; b++) {
			expected[b] = new long[n];
			for (int i = 0; i < n; i++) {
				long at = (long) SLOT.invoke(layer, b);
				int id = stamp(b, i, iteration);
				for (int word = 0; word < 16; word++) MemoryUtil.memPutInt(at + word * 4L, id ^ word);
				if (layer.qaddr[b] != 0) for (int word = 0; word < OwnQrec.BYTES / 4; word++)
					MemoryUtil.memPutInt(layer.qaddr[b] + (long) i * OwnQrec.BYTES + word * 4L, ~id ^ word);
				layer.plane[b][i] = (i * 17 % 257) / 16f;
				layer.lo[b][i] = (i * 31 % 32) | (i * 7 % 32) << 8 | (i * 13 % 32) << 16;
				layer.hi[b][i] = layer.lo[b][i] + 0x010101;
				layer.mat[b][i] = id;
				layer.cls[b][i] = (byte) (i % 6);
				expected[b][i] = (long) KEY.invoke(null, b, layer.plane[b][i], layer.lo[b][i], i);
				layer.count[b]++; layer.total++;
			}
			Arrays.sort(expected[b]);
		}
		return expected;
	}
	public static void main(String[] args) throws Exception {
		if (!OwnQuads.ORDER) throw new AssertionError("Enable mesh.order=cell");
		var layer = new OwnQuads.Layer();
		var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().threadId();
		try {
			int[] sizes = {2, 129, 4097, 7, 512, 65, 512, 512};
			for (int iteration = 0; iteration < 140; iteration++) {
				int n = iteration < sizes.length ? sizes[iteration] : iteration == 138 ? 4097 : 7;
				long[][] expected = fill(layer, n, iteration);
				long before = allocations.getThreadAllocatedBytes(thread), start = System.nanoTime();
				layer.order();
				long nanos = System.nanoTime() - start, bytes = allocations.getThreadAllocatedBytes(thread) - before;
				for (int b = 0; b < 7; b++) for (int i = 0; i < n; i++) {
					int original = (int) expected[b][i] & 0xFFFFFF, id = stamp(b, original, iteration);
					int lo = (original * 31 % 32) | (original * 7 % 32) << 8 | (original * 13 % 32) << 16;
					if (layer.plane[b][i] != (original * 17 % 257) / 16f || layer.lo[b][i] != lo || layer.hi[b][i] != lo + 0x010101
						|| layer.mat[b][i] != id || layer.cls[b][i] != original % 6)
						throw new AssertionError("Metadata permutation changed after reuse");
					for (int word = 0; word < 16; word++)
						if (MemoryUtil.memGetInt(layer.addr[b] + (long) i * 64 + word * 4L) != (id ^ word))
							throw new AssertionError("Quad bytes differ from original sort");
					if (layer.qaddr[b] != 0) for (int word = 0; word < OwnQrec.BYTES / 4; word++)
						if (MemoryUtil.memGetInt(layer.qaddr[b] + (long) i * OwnQrec.BYTES + word * 4L) != (~id ^ word))
							throw new AssertionError("Record bytes differ from original sort");
				}
				layer.order(); // Multiple consumers of a layer must preserve exactly the same permutation.
				if (iteration == 137) for (int cap : layer.cap) if (cap > 128) throw new AssertionError("Worker high-water buffers were not trimmed");
				if (iteration < sizes.length || iteration >= 137) System.out.printf("ORDER iteration=%d quadsPerBucket=%d allocatedBytes=%d timeMs=%.4f%n", iteration, n, bytes, nanos / 1e6);
			}
			System.out.println("PASS: all seven buckets preserve quad/record bytes, planes, bounds, material and class through grow/shrink/reuse");
		} finally {
			layer.releaseNative(); layer.releaseNative();
			for (int b = 0; b < 7; b++) if (layer.addr[b] != 0 || layer.qaddr[b] != 0) throw new AssertionError("Native scratch not released exactly once");
		}
	}
}
