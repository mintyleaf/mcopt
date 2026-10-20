package mcopt.metal.lod;

import java.util.List;
import java.util.Collection;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.ToIntFunction;

/** Separate eligibility lanes avoid repeatedly taking a blocked priority head. Selection examines four heads only. */
final class LodJobQueue<T extends Comparable<? super T>> {
	static final int CACHE = 0, COARSE = 1, VISIBLE = 2, OTHER = 3;
	private final List<PriorityBlockingQueue<T>> lanes = List.of(new PriorityBlockingQueue<>(), new PriorityBlockingQueue<>(),
		new PriorityBlockingQueue<>(), new PriorityBlockingQueue<>());
	private final ToIntFunction<T> classify;
	LodJobQueue(ToIntFunction<T> classify) { this.classify = classify; }
	void add(T job) { this.lanes.get(this.classify.applyAsInt(job)).add(job); }

	T poll(boolean coarse, boolean visible, boolean other) {
		T best = null;
		int selected = -1;
		for (int lane = 0; lane < this.lanes.size(); lane++) {
			if (lane == COARSE && !coarse || lane == VISIBLE && !visible || lane == OTHER && !other) continue;
			T head = this.lanes.get(lane).peek();
			if (head != null && (best == null || head.compareTo(best) < 0)) { best = head; selected = lane; }
		}
		// Another worker/scanner may have taken this head. Admission is checked again before generation starts.
		return selected < 0 ? null : this.lanes.get(selected).poll();
	}

	void drainTo(Collection<? super T> out) { for (var lane : this.lanes) lane.drainTo(out); }
	int size(int lane) { return this.lanes.get(lane).size(); }
	int size() { int n = 0; for (var lane : this.lanes) n += lane.size(); return n; }
	boolean isEmpty() { for (var lane : this.lanes) if (!lane.isEmpty()) return false; return true; }
}
