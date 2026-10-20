package mcopt.metal.lod;

import java.util.ArrayDeque;

/** One pending immutable request per ring slot. Consuming a request also retires its version metadata. */
final class LodMeshRequests {
	record Request(long key, long sequence, long version) { }
	private final Request[] pending;
	private final boolean[] enqueued;
	private final long[] latest;
	private final ArrayDeque<Integer> ready = new ArrayDeque<>();
	private final int tps;

	LodMeshRequests(int levels, int tps) {
		this.tps = tps;
		this.pending = new Request[levels * tps * tps];
		this.enqueued = new boolean[this.pending.length];
		this.latest = new long[this.pending.length];
	}

	private int index(int level, int tx, int tz) {
		return level * this.tps * this.tps + (tz & (this.tps - 1)) * this.tps + (tx & (this.tps - 1));
	}

	synchronized void offer(int level, int tx, int tz, long sequence, long version) {
		int i = this.index(level, tx, tz);
		this.pending[i] = new Request(LodTile.key(level, tx, tz), sequence, version);
		this.latest[i] = sequence;
		if (!this.enqueued[i]) { this.enqueued[i] = true; this.ready.add(i); }
		this.notifyAll();
	}

	synchronized Request take() throws InterruptedException {
		while (true) {
			while (this.ready.isEmpty()) this.wait();
			int i = this.ready.remove();
			this.enqueued[i] = false;
			Request request = this.pending[i]; this.pending[i] = null;
			if (request != null) return request;
		}
	}

	synchronized boolean current(Request request) { return this.current(request.key, request.sequence); }
	synchronized boolean current(long key, long sequence) {
		return this.latest[this.index(LodTile.levelOf(key), LodTile.txOf(key), LodTile.tzOf(key))] == sequence;
	}

	/** A raced worker must not replace a newer coordinate/version after the ring moves. */
	synchronized boolean retry(Request request) {
		int i = this.index(LodTile.levelOf(request.key), LodTile.txOf(request.key), LodTile.tzOf(request.key));
		if (this.latest[i] != request.sequence) return false;
		this.offer(LodTile.levelOf(request.key), LodTile.txOf(request.key), LodTile.tzOf(request.key), request.sequence, request.version);
		return true;
	}

	synchronized void cancel(int level, int slot) {
		int i = level * this.tps * this.tps + slot;
		this.pending[i] = null; this.latest[i] = 0;
	}
	synchronized int size() { return this.ready.size(); }
	synchronized void clear() {
		java.util.Arrays.fill(this.pending, null);
		java.util.Arrays.fill(this.enqueued, false);
		java.util.Arrays.fill(this.latest, 0);
		this.ready.clear();
	}
}
