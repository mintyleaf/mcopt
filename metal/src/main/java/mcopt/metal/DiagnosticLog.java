package mcopt.metal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Periodic diagnostic text only. Slow console/file output must never hold a render or generation thread. */
public final class DiagnosticLog {
	private static final Sink OUTPUT = new Sink(64, 8192, line -> System.out.println(line));

	private DiagnosticLog() { }

	public static void println(Object message) {
		OUTPUT.offer(String.valueOf(message));
	}

	public static void printf(String format, Object... values) {
		printf(java.util.Locale.ROOT, format, values);
	}

	public static void printf(java.util.Locale locale, String format, Object... values) {
		OUTPUT.offer(String.format(locale, format, values).stripTrailing());
	}

	public static long dropped() { return OUTPUT.dropped(); }

	/** Immutable, size-limited strings only: never enqueue game objects, native handles, closures or unbounded work. */
	static final class Sink implements AutoCloseable {
		private final ArrayBlockingQueue<String> queue;
		private final int maxChars;
		private final AtomicLong dropped = new AtomicLong();
		private final Thread worker;

		Sink(int capacity, int maxChars, Consumer<String> writer) {
			this.queue = new ArrayBlockingQueue<>(capacity);
			if (maxChars < 3) throw new IllegalArgumentException("Diagnostic line limit must be at least 3");
			this.maxChars = maxChars;
			this.worker = Thread.ofPlatform().name("mcopt-diagnostics").daemon(true).unstarted(() -> {
				try {
					while (!Thread.currentThread().isInterrupted()) {
						String line = this.queue.take();
						try { writer.accept(line); }
						catch (RuntimeException failed) { this.dropped.incrementAndGet(); }
					}
				} catch (InterruptedException stopped) {
					Thread.currentThread().interrupt();
				}
			});
			this.worker.start();
		}

		boolean offer(String line) {
			String bounded = line.length() <= this.maxChars ? line : line.substring(0, this.maxChars - 3) + "...";
			if (this.queue.offer(bounded)) return true;
			this.dropped.incrementAndGet();
			return false; // Keep rendering even if the output writer stays blocked; memory remains bounded.
		}

		long dropped() { return this.dropped.get(); }

		@Override
		public void close() { this.worker.interrupt(); }
	}
}
