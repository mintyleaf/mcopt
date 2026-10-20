package mcopt.metal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.lwjgl.system.MemoryUtil;

/** Actual pre/main command buffers recorded through the production encoder and JFR event writer. */
public final class MetalTimingChecks {
	public static void main(String[] args) throws Exception {
		System.setProperty("mcopt.metal.jfr", "true");
		Path output = args.length > 0 ? Path.of(args[0]) : Files.createTempFile("mcopt-metal-timing-", ".jfr");
		long ctx = MetalBridge.createHeadlessContext();
		MetalEncoder encoder = new MetalEncoder(ctx);
		long source = Native.bufferNew(ctx, 4096), destination = Native.bufferNew(ctx, 4096);
		try (Recording recording = new Recording()) {
			recording.enable("mcopt.MetalCommandBuffer");
			recording.enable("mcopt.MetalPresentation");
			recording.enable("mcopt.MetalPipelineCompile");
			recording.enable("mcopt.MetalOperation").withThreshold(Duration.ZERO).withoutStackTrace();
			recording.enable("mcopt.MetalDrawable");
			recording.start();
			// Serialization fixture: nil identity avoids inventing a retained drawable in a headless context.
			// Real driver identity and pixels are covered by the native drawable-acquire check.
			var lifetime = MetalEvents.drawableBegin(8, 0, 17, 3, 640, 480, true);
			if (lifetime == null) throw new AssertionError("Drawable lifetime recording not enabled");
			MetalEvents.drawableEnd(lifetime, true, true);
			MetalEvents.drawableEnd(MetalEvents.drawableBegin(9, 0, 17, 4, 800, 600, false), false, true);
			if (MetalSurface.drawableWaitNanos(60)<20_000_000 || MetalSurface.drawableWaitNanos(144)>12_000_000
				|| MetalSurface.drawableWaitNanos(0)!=MetalSurface.drawableWaitNanos(60)) throw new AssertionError("Drawable wait not tied to refresh period");
			// A missing layer must still return nil, with native-only wall/thread CPU timing serialized.
			var drawableEvent = encoder.drawableEvent();
			try {
				if (MetalEvents.nextDrawable(0, drawableEvent) != 0) throw new AssertionError("Missing layer produced a drawable");
			} finally {
				MetalEvents.end(drawableEvent);
			}
			long acquire=Native.drawableAcquireNew(0);
			try {
				for(int mode=0;mode<2;mode++) {
					if(mode==1) Native.drawableAcquireClose(acquire);
					var bounded=encoder.drawableEvent();
					try {
						if(MetalEvents.nextDrawableBounded(acquire,1_000_000_000L,bounded)!=0
							|| bounded.drawableStatus!=(mode==0?0:3)) throw new AssertionError("Bounded nil/closed status changed");
					} finally { MetalEvents.end(bounded); }
				}
			} finally { Native.drawableAcquireClose(acquire);Native.release(acquire); }
			Object poll=MetalBridge.windowEventsBegin();MetalBridge.windowEventsEnd(poll);
			for (int frame = 0; frame < 4; frame++) {
				Native.preFill(encoder.enc, source, 0, 4096, frame + 1);
				Native.blitBuffer(encoder.enc, source, 0, destination, 0, 4096);
				encoder.submit();
			}
			encoder.waitIdle();
			// A post-GPU callback failure must still release/record the command buffer's CPU ownership.
			encoder.afterGpuFinishes(() -> { throw new IllegalStateException("retirement fixture"); });
			encoder.submit();
			try {
				encoder.waitIdle();
				throw new AssertionError("Retirement callback did not run");
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().equals("retirement fixture")) throw expected;
			}
			if (MemoryUtil.memGetInt(Native.bufferContents(destination)) != 0x04040404)
				throw new AssertionError("Timing instrumentation changed pre/main execution order");
			// Missing scanout must survive serialization as missing, not a synthetic zero-latency presentation.
			MetalEvents.presentation(7, 0, 0, true);
			recording.stop(); recording.dump(output);
		} finally {
			encoder.waitIdle(); Native.release(source); Native.release(destination);
		}
		int main = 0, pre = 0, missing = 0, drawable = 0, poll = 0, bounded = 0, lifetimes = 0;
		if (MetalEvents.drawableBegin(0, 0, 0, 0, 0, 0, false) != null)
			throw new AssertionError("Inactive drawable telemetry still creates events");
		try (RecordingFile recording = new RecordingFile(output)) {
			while (recording.hasMoreEvents()) {
				var event = recording.readEvent();
				if (event.getEventType().getName().equals("mcopt.MetalDrawable")) {
					boolean first = event.getLong("submit") == 8;
					if (event.getLong("layer") != 17 || event.getLong("surfaceGeneration") != (first ? 3 : 4)
						|| event.getLong("drawableId") != 0 || event.getInt("requestedWidth") != (first ? 640 : 800)
						|| event.getInt("requestedHeight") != (first ? 480 : 600) || event.getBoolean("vsync") != first
						|| event.getBoolean("presentScheduled") != first || !event.getBoolean("released")
						|| event.getDouble("acquiredAt") <= 0 || event.getDouble("releasedAt") < event.getDouble("acquiredAt"))
						throw new AssertionError("Drawable ownership event lost identity, configuration, release or present status");
					lifetimes++;
				}
				if(event.getEventType().getName().equals("mcopt.MetalOperation") && event.getString("operation").equals("pollEvents")) poll++;
				if (event.getEventType().getName().equals("mcopt.MetalOperation") && event.getString("operation").equals("nextDrawable")) {
					if (event.getDouble("nativeStart") <= 0 || event.getDouble("nativeEnd") < event.getDouble("nativeStart")
						|| event.getDouble("nativeThreadCpuMs") < 0) throw new AssertionError("Missing native drawable timing");
					drawable++;
					if(event.getDouble("drawableWorkerStart")>0) {
						if(event.getDouble("drawableWorkerEnd")<event.getDouble("drawableWorkerStart") || event.getDouble("drawableWorkerCpuMs")<0)
							throw new AssertionError("Invalid worker timeline");
						bounded++;
					}
				}
				if (event.getEventType().getName().equals("mcopt.MetalPresentation")) {
					if (!event.getBoolean("missing") || event.getDouble("presentedAt") != 0) throw new AssertionError("Missing scanout was reinterpreted");
					missing++;
				}
				if (!event.getEventType().getName().equals("mcopt.MetalCommandBuffer")) continue;
				if (event.getInt("status") != 4 || event.getDouble("committedAt") <= 0 || event.getDouble("gpuStart") <= 0
					|| event.getDouble("gpuEnd") < event.getDouble("gpuStart") || event.getDouble("observedAt") < event.getDouble("gpuEnd")
					|| !event.getBoolean("released") || event.getDouble("releasedAt") < event.getDouble("observedAt"))
					throw new AssertionError("Invalid completed command-buffer timeline: " + event);
				if (event.getString("kind").equals("main")) main++; else if (event.getString("kind").equals("pre")) pre++;
			}
		}
		if (main != 5 || pre != 4 || missing != 1 || drawable != 3 || poll!=1 || bounded!=1 || lifetimes!=2) throw new AssertionError("Missing timing events");
		System.out.println("PASS: production encoder preserves data and emits drawable ownership plus complete GPU/CPU-release timelines, including callback failure; missing scanout stays explicit: " + output);
	}
}
