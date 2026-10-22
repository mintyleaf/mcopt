package mcopt.metal.lod;

import org.lwjgl.system.MemoryUtil;

/** Exact, bounded key for the ordinary mesh cull's retained GPU outputs. No rounded camera or hash collisions. */
final class LodCullReuse {
	private final int[] mesh = new int[LodMesh.FRAME_BYTES / 4];
	private final int[] comp = new int[57]; // 0..128, runs offset, 192..256, handoff window/band.
	private final int[] mask = new int[128 * 4];
	private long meshRevision, clipRevision, arena;
	private int words;
	private boolean valid;
	long reused, dispatched;

	void invalidate() { this.valid = false; }

	boolean matches(long frame, long composite, long meshRevision, long clipRevision, long arena, int[] mask, int words) {
		if (!this.valid || this.meshRevision != meshRevision || this.clipRevision != clipRevision || this.arena != arena || this.words != words) return false;
		for (int i = 0; i < this.mesh.length; i++) if (this.mesh[i] != MemoryUtil.memGetInt(frame + i * 4L)) return false;
		for (int i = 0; i < this.comp.length; i++) if (this.comp[i] != MemoryUtil.memGetInt(composite + compOffset(i))) return false;
		for (int i = 0; i < words; i++) if (this.mask[i] != mask[i]) return false;
		this.reused++;
		return true;
	}

	/** Called only after the native cull was encoded successfully. */
	void remember(long frame, long composite, long meshRevision, long clipRevision, long arena, int[] mask, int words) {
		for (int i = 0; i < this.mesh.length; i++) this.mesh[i] = MemoryUtil.memGetInt(frame + i * 4L);
		for (int i = 0; i < this.comp.length; i++) this.comp[i] = MemoryUtil.memGetInt(composite + compOffset(i));
		System.arraycopy(mask, 0, this.mask, 0, words);
		this.meshRevision = meshRevision; this.clipRevision = clipRevision; this.arena = arena; this.words = words;
		this.valid = true; this.dispatched++;
	}

	private static int compOffset(int i) {
		if (i < 32) return i * 4;
		if (i == 32) return 172;
		if (i < 49) return 192 + (i - 33) * 4;
		return Lod.COMP_FRAME_BYTES - 32 + (i - 49) * 4;
	}
}
