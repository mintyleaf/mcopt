package mcopt.metal.lod;

import java.util.concurrent.CompletableFuture;

/** One generation future and its loading ticket. Cancelling releases the ticket but waits for the future to settle. */
final class LodRefineGate<T> {
	private CompletableFuture<T> future;
	private Runnable release;
	private boolean cancelled;

	boolean busy() { return this.future != null; }
	boolean done() { return this.future != null && this.future.isDone(); }
	boolean cancelled() { return this.cancelled; }

	void start(CompletableFuture<T> future, Runnable release) {
		if (this.busy()) throw new IllegalStateException("Refinement already active");
		this.future = future;
		this.release = release;
		this.cancelled = false;
	}

	T result() { return this.future.join(); }

	void cancel() {
		this.cancelled = true;
		this.release();
	}

	void finish() {
		try { this.release(); }
		finally { this.future = null; }
	}

	private void release() {
		Runnable action = this.release;
		this.release = null;
		if (action != null) action.run();
	}
}
