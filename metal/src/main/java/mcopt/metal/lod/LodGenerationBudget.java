package mcopt.metal.lod;

/** Counts every running generation, including jobs admitted before pressure arrived. Cache reads do not acquire slots. */
final class LodGenerationBudget {
	private int coarse, fine;
	private long revision;

	synchronized boolean acquire(boolean fallback, boolean pressure, int coarseLimit, int fineLimit) {
		return this.acquire(fallback, pressure, coarseLimit, fineLimit, Integer.MAX_VALUE);
	}

	synchronized boolean available(boolean fallback, boolean pressure, int coarseLimit, int fineLimit, int totalLimit) {
		return !pressure || (this.coarse + this.fine < totalLimit && (fallback ? this.coarse < coarseLimit : this.fine < fineLimit));
	}

	synchronized boolean acquire(boolean fallback, boolean pressure, int coarseLimit, int fineLimit, int totalLimit) {
		if (!this.available(fallback, pressure, coarseLimit, fineLimit, totalLimit)) return false;
		if (fallback) this.coarse++; else this.fine++;
		return true;
	}

	synchronized void release(boolean fallback) {
		if (fallback) { if (--this.coarse < 0) throw new IllegalStateException("Unbalanced coarse LOD admission"); }
		else if (--this.fine < 0) throw new IllegalStateException("Unbalanced fine LOD admission");
		this.signal();
	}

	synchronized void signal() { this.revision++; this.notifyAll(); }
	synchronized long revision() { return this.revision; }
	synchronized int coarse() { return this.coarse; }
	synchronized int fine() { return this.fine; }

	/** New work, completed jobs or coverage changes wake waiters; the timeout also bounds a missed external change. */
	synchronized void await(long observed) throws InterruptedException {
		if (observed == this.revision) this.wait(50);
	}

	synchronized void awaitWork(long observed) throws InterruptedException {
		if (observed == this.revision) this.wait();
	}
}
