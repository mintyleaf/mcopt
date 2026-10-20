package mcopt.metal.lod;

import java.util.ArrayDeque;

/** Reuses complete tile planes only after all snapshot leases end. The idle budget is per world, not per shape. */
final class LodSnapshotPool {
	private static final long LIMIT = 8L << 20;
	private final ArrayDeque<Words>[] idle;
	private long bytes;
	private long borrowed;
	private boolean closed;
	long reused;

	@SuppressWarnings("unchecked")
	LodSnapshotPool() {
		this.idle = (ArrayDeque<Words>[]) new ArrayDeque<?>[8];
		for (int i = 0; i < this.idle.length; i++) this.idle[i] = new ArrayDeque<>();
	}

	Words acquire(boolean crowns, boolean texture, boolean plants) {
		int shape = (crowns ? 1 : 0) | (texture ? 2 : 0) | (plants ? 4 : 0);
		synchronized (this) {
			this.borrowed++;
			Words words = this.idle[shape].pollLast();
			if (words != null) {
				this.bytes -= words.bytes;
				this.reused++;
				return words;
			}
		}
		return new Words(shape);
	}

	synchronized void recycle(Words words) {
		this.borrowed--;
		if (this.closed || this.bytes + words.bytes > LIMIT) return;
		this.idle[words.shape].addLast(words);
		this.bytes += words.bytes;
	}

	synchronized long idleBytes() { return this.bytes; }
	synchronized long borrowedWords() { return this.borrowed; }

	synchronized void close() {
		this.closed = true;
		for (var bin : this.idle) bin.clear();
		this.bytes = 0;
	}

	static final class Words {
		final int shape;
		final int[] g = new int[LodClip.TILE * LodClip.TILE], c = new int[LodClip.TILE * LodClip.TILE];
		final int @org.jspecify.annotations.Nullable [] cr, runs, tw, pl;
		final long bytes;

		Words(int shape) {
			this.shape = shape;
			int n = LodClip.TILE * LodClip.TILE;
			this.cr = (shape & 1) != 0 ? new int[n] : null;
			this.runs = (shape & 1) != 0 ? new int[n] : null;
			this.tw = (shape & 2) != 0 ? new int[LodClip.surfaceWords()] : null;
			this.pl = (shape & 4) != 0 ? new int[2 * n] : null;
			this.bytes = 4L * (2 * n + (this.cr == null ? 0 : 2 * n) + (this.tw == null ? 0 : this.tw.length)
				+ (this.pl == null ? 0 : this.pl.length));
		}
	}
}
