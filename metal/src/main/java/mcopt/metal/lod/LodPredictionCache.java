package mcopt.metal.lod;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** Shared weighted LRU for prediction data. Computations run outside the monitor and same-key misses share one result. */
final class LodPredictionCache {
	private record Key(int kind, long coordinate) { }
	private record Entry(Object value, long bytes) { }
	private final long limit;
	private final int entryLimit;
	private long bytes;
	private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(128, .75F, true);
	private final HashMap<Key, CompletableFuture<Object>> pending = new HashMap<>();

	LodPredictionCache(long limit, int entryLimit) {
		if (limit <= 0 || entryLimit <= 0) throw new IllegalArgumentException("Prediction budget must be positive");
		this.limit = limit; this.entryLimit = entryLimit;
	}

	@SuppressWarnings("unchecked")
	<T> T get(int kind, long coordinate, Supplier<T> compute, ToLongFunction<T> weight) {
		Key key = new Key(kind, coordinate);
		CompletableFuture<Object> future;
		boolean owner;
		synchronized (this) {
			Entry hit = this.entries.get(key);
			if (hit != null) return (T) hit.value;
			future = this.pending.get(key); owner = future == null;
			if (owner) { future = new CompletableFuture<>(); this.pending.put(key, future); }
		}
		if (!owner) return (T) future.join();
		try {
			T value = compute.get();
			long size = Math.max(1, weight.applyAsLong(value));
			synchronized (this) {
				Entry offered = this.entries.get(key);
				if (offered != null) value = (T) offered.value;
				else this.insert(key, value, size);
				future.complete(value); this.pending.remove(key);
			}
			return value;
		} catch (RuntimeException | Error failure) {
			synchronized (this) { future.completeExceptionally(failure); this.pending.remove(key); }
			throw failure;
		}
	}

	synchronized boolean contains(int kind, long coordinate) { return this.entries.containsKey(new Key(kind, coordinate)); }

	synchronized void offer(int kind, long coordinate, Object value, long weight) {
		Key key = new Key(kind, coordinate);
		if (!this.entries.containsKey(key)) this.insert(key, value, Math.max(1, weight));
	}

	private void insert(Key key, Object value, long weight) {
		if (weight > this.limit) return; // Oversized features may be consumed but never retained beyond the budget.
		this.entries.put(key, new Entry(value, weight)); this.bytes += weight;
		while (this.bytes > this.limit || this.entries.size() > this.entryLimit) {
			var first = this.entries.entrySet().iterator();
			this.bytes -= first.next().getValue().bytes; first.remove();
		}
	}

	synchronized long bytes() { return this.bytes; }
	synchronized int size() { return this.entries.size(); }
	synchronized int computing() { return this.pending.size(); }
}
