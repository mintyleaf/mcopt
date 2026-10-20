package mcopt.metal.own;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Best-fit range allocator over [0, capacity) in units (quads, records): free ranges by start (for merging neighbours) and by
 * size then start (best fit). Shared operations lock this allocator. An optional render reserve has its own lock,
 * so allocating transient render data need not wait for a descheduled meshing worker holding the shared allocator.
 */
final class OwnAlloc {
	private final TreeMap<Integer, Integer> byStart = new TreeMap<>();
	private final TreeSet<Long> bySize = new TreeSet<>();
	private int capacity;
	private long used;
	private Reserve reserve;
	private final java.util.concurrent.locks.ReentrantLock sharedLock = new java.util.concurrent.locks.ReentrantLock();
	private final Guard guard = new Guard();
	final class Guard implements AutoCloseable {
		@Override public void close() { sharedLock.unlock(); }
	}
	Guard locked() { this.sharedLock.lock(); return this.guard; }
	private record Freed(int start, int units) { }
	private static final class Reserve {
		final Thread owner;
		final int start, units;
		final OwnAlloc ranges;
		final java.util.concurrent.ConcurrentLinkedQueue<Freed> frees = new java.util.concurrent.ConcurrentLinkedQueue<>();
		long hits, misses;
		Reserve(Thread owner, int start, int units) {
			this.owner = owner; this.start = start; this.units = units; this.ranges = new OwnAlloc(units);
		}
	}

	OwnAlloc(int capacity) {
		this.capacity = capacity;
		this.addFree(0, capacity);
	}

	int capacity() { try (var ignored = this.locked()) { return this.capacity; } }

	long used() {
		try (var ignored = this.locked()) {
			this.drainFrees();
			return this.used - (this.reserve == null ? 0 : this.reserve.units - this.reserve.ranges.used());
		}
	}

	/** Construction only, after any required prefix (such as the exact-position table). No new native memory. */
	void reserveFor(Thread owner, int units) {
		try (var ignored = this.locked()) {
			if (this.reserve != null || owner == null || units <= 0) throw new IllegalArgumentException("Invalid allocator reserve");
			int start = this.allocShared(units);
			if (start < 0) throw new IllegalStateException("Allocator reserve does not fit");
			this.reserve = new Reserve(owner, start, units);
		}
	}

	int reserveUnits() { return this.reserve == null ? 0 : this.reserve.units; }
	long reserveUsed() { return this.reserve == null ? 0 : this.reserve.ranges.used(); }
	long reserveHits() { return this.reserve == null ? 0 : this.reserve.hits; }
	long reserveMisses() { return this.reserve == null ? 0 : this.reserve.misses; }

	/** Start of a free range of n units, or -1 when none is big enough. */
	int alloc(int n) {
		Reserve r = this.reserve;
		if (r != null && Thread.currentThread() == r.owner) {
			int start = r.ranges.alloc(n);
			if (start >= 0) { r.hits++; return r.start + start; }
			r.misses++;
		}
		try (var ignored = this.locked()) { return this.allocShared(n); }
	}

	/** Optional render work may use the existing full order if its allocation would wait or grow the arena. */
	int tryAlloc(int n) {
		Reserve r = this.reserve;
		if (r != null && Thread.currentThread() == r.owner) {
			int start = r.ranges.alloc(n);
			if (start >= 0) { r.hits++; return r.start + start; }
			r.misses++;
		}
		if (!this.sharedLock.tryLock()) return -1;
		try { return this.allocShared(n); }
		finally { this.sharedLock.unlock(); }
	}

	private int allocShared(int n) {
		this.drainFrees();
		Long fit = this.bySize.ceiling((long) n << 32);
		if (fit == null) return -1;
		int start = (int) (long) fit, size = (int) (fit >>> 32);
		this.removeFree(start, size);
		if (size > n) this.addFree(start + n, size - n);
		this.used += n;
		return start;
	}

	void free(int start, int n) {
		if (n <= 0) return;
		Reserve r = this.reserve;
		if (r != null && start >= r.start && start < r.start + r.units) {
			if ((long) start + n > r.start + r.units) throw new IllegalArgumentException("Free crosses allocator reserve");
			r.ranges.free(start - r.start, n);
			return;
		}
		if (r != null) {
			// The caller already waited for GPU retirement. Publishing a free must not wait for a meshing worker.
			r.frees.add(new Freed(start, n));
			return;
		}
		try (var ignored = this.locked()) { this.freeShared(start, n); }
	}

	/** Called with the shared lock held, before allocation or live-use accounting. */
	private void drainFrees() {
		if (this.reserve == null) return;
		for (Freed range; (range = this.reserve.frees.poll()) != null; ) this.freeShared(range.start, range.units);
	}

	private void freeShared(int start, int n) {
		this.used -= n;
		Map.Entry<Integer, Integer> before = this.byStart.floorEntry(start - 1);
		if (before != null && before.getKey() + before.getValue() == start) {
			this.removeFree(before.getKey(), before.getValue());
			start = before.getKey();
			n += before.getValue();
		}
		Integer after = this.byStart.get(start + n);
		if (after != null) {
			this.removeFree(start + n, after);
			n += after;
		}
		this.addFree(start, n);
	}

	/** Capacity grows to newCapacity; the new tail is free. */
	void grow(int newCapacity) {
		try (var ignored = this.locked()) {
			if (newCapacity <= this.capacity) return;
			int old = this.capacity;
			this.capacity = newCapacity;
			this.used += newCapacity - old;  // free() below takes it back out
			this.freeShared(old, newCapacity - old);
		}
	}

	private void addFree(int start, int size) {
		this.byStart.put(start, size);
		this.bySize.add((long) size << 32 | start);
	}

	private void removeFree(int start, int size) {
		this.byStart.remove(start);
		this.bySize.remove((long) size << 32 | start);
	}
}
