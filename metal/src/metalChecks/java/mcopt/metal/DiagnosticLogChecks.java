package mcopt.metal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A blocked output writer must not block producers, grow the queue, or retain mutable messages. */
public final class DiagnosticLogChecks {
	public static void main(String[] args) throws Exception {
		var entered = new CountDownLatch(1);
		var unblock = new CountDownLatch(1);
		var drained = new CountDownLatch(5);
		var output = new CopyOnWriteArrayList<String>();
		var wrongThread = new AtomicBoolean();
		Thread producer = Thread.currentThread();
		try (var sink = new DiagnosticLog.Sink(4, 16, message -> {
			if (Thread.currentThread() == producer) wrongThread.set(true);
			entered.countDown();
			try { if (!unblock.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer gate not released"); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
			output.add(message); drained.countDown();
		})) {
			if (!sink.offer("first") || !entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer did not start");
			var produced = new CountDownLatch(1);
			Thread.ofPlatform().daemon(true).start(() -> {
				for (int i=0; i<100_000; i++) sink.offer(i == 0 ? "012345678901234567890123456789" : "queued-" + i);
				produced.countDown();
			});
			try {
				if (!produced.await(2, TimeUnit.SECONDS)) throw new AssertionError("Blocked writer held a producer");
				if (sink.dropped() != 99_996) throw new AssertionError("Diagnostic queue is not bounded: " + sink.dropped());
			} finally { unblock.countDown(); }
			if (!drained.await(5, TimeUnit.SECONDS)) throw new AssertionError("Accepted diagnostics not drained");
			if (wrongThread.get() || !output.equals(List.of("first", "0123456789012...", "queued-1", "queued-2", "queued-3")))
				throw new AssertionError("Diagnostics lost order, length bounds or worker isolation: " + output);
		}
		var recovered = new CountDownLatch(1);
		try (var sink = new DiagnosticLog.Sink(4, 32, message -> {
			if (message.equals("bad")) throw new IllegalStateException("Simulated output failure");
			recovered.countDown();
		})) {
			sink.offer("bad"); sink.offer("good");
			if (!recovered.await(5, TimeUnit.SECONDS) || sink.dropped() != 1) throw new AssertionError("Writer failure killed diagnostics");
		}
		System.out.println("PASS: blocked writer cannot block 100,000 producer offers; queue and line lengths stay bounded, accepted order survives, output failures recover");
	}
}
