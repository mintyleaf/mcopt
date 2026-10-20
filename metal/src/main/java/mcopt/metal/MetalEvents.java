package mcopt.metal;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Opt-in CPU-side spans. JFR timestamps line these up with GC, samples and benchmark frames. */
final class MetalEvents {
	private static final boolean ENABLED = Boolean.getBoolean("mcopt.metal.jfr");

	@Name("mcopt.MetalCommandBuffer")
	@Label("Metal command buffer timeline")
	@Category("mcopt")
	@StackTrace(false)
	static final class CommandBuffer extends Event {
		long submit;
		String kind;
		int status;
		/** Native monotonic host seconds; observedAt maps them onto this event's JFR timestamp. */
		double committedAt, kernelStart, kernelEnd, gpuStart, gpuEnd, observedAt, releasedAt;
		boolean released;
	}

	private static final jdk.jfr.EventType COMMAND_TYPE = jdk.jfr.EventType.getEventType(CommandBuffer.class);
	static boolean commandBuffersEnabled() { return ENABLED && COMMAND_TYPE.isEnabled(); }

	@Name("mcopt.MetalPresentation")
	@Label("Metal presentation observation")
	@Category("mcopt")
	@StackTrace(false)
	static final class Presentation extends Event {
		long submit;
		double presentedAt, callbackAt, observedAt;
		boolean missing;
	}
	private static final jdk.jfr.EventType PRESENTATION_TYPE = jdk.jfr.EventType.getEventType(Presentation.class);
	static boolean presentationsEnabled() { return ENABLED && PRESENTATION_TYPE.isEnabled(); }

	@Name("mcopt.MetalDrawable")
	@Label("Metal drawable CPU ownership")
	@Category("mcopt")
	@StackTrace(false)
	static final class Drawable extends Event {
		long submit, layer, surfaceGeneration, drawableId;
		double acquiredAt, releasedAt;
		int requestedWidth, requestedHeight;
		boolean vsync, presentScheduled, released;
	}
	private static final jdk.jfr.EventType DRAWABLE_TYPE = jdk.jfr.EventType.getEventType(Drawable.class);

	static Drawable drawableBegin(long submit, long drawable, long layer, long generation, int width, int height, boolean vsync) {
		if (!ENABLED || !DRAWABLE_TYPE.isEnabled()) return null;
		Drawable event = new Drawable();
		event.submit = submit; event.layer = layer; event.surfaceGeneration = generation;
		event.drawableId = Native.drawableId(drawable); event.acquiredAt = Native.hostSeconds();
		event.requestedWidth = width; event.requestedHeight = height; event.vsync = vsync;
		event.begin();
		return event;
	}

	static void drawableEnd(Drawable event, boolean scheduled, boolean released) {
		if (event == null) return;
		event.end(); event.releasedAt = released ? Native.hostSeconds() : 0;
		event.presentScheduled = scheduled; event.released = released;
		event.commit();
	}
	static void presentation(long submit, long presented, long callback, boolean missing) {
		if (!presentationsEnabled()) return;
		Presentation event = new Presentation();
		event.submit = submit; event.presentedAt = presented / 1e9; event.callbackAt = callback / 1e9;
		event.observedAt = Native.hostSeconds(); event.missing = missing;
		event.commit();
	}

	static CommandBuffer commandBufferBegin(long submit, String kind, long cmd, double committedAt) {
		if (!commandBuffersEnabled() || committedAt == 0) return null;
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, 48);
			Native.cmdTiming(cmd, out);
			CommandBuffer event = new CommandBuffer();
			event.begin(); // Anchor startTime beside observedAt, even when retirement callbacks delay CPU release.
			event.submit = submit; event.kind = kind; event.committedAt = committedAt;
			event.kernelStart = org.lwjgl.system.MemoryUtil.memGetDouble(out);
			event.kernelEnd = org.lwjgl.system.MemoryUtil.memGetDouble(out + 8);
			event.gpuStart = org.lwjgl.system.MemoryUtil.memGetDouble(out + 16);
			event.gpuEnd = org.lwjgl.system.MemoryUtil.memGetDouble(out + 24);
			event.observedAt = org.lwjgl.system.MemoryUtil.memGetDouble(out + 32);
			event.status = (int) org.lwjgl.system.MemoryUtil.memGetDouble(out + 40);
			return event;
		}
	}

	static void commandBufferEnd(CommandBuffer event, boolean released) {
		if (event == null) return;
		event.released = released;
		event.releasedAt = released ? Native.hostSeconds() : 0;
		event.end();
		event.commit();
	}

	@Name("mcopt.MetalPipelineCompile")
	@Label("Metal pipeline compilation")
	@Category("mcopt")
	@StackTrace(false)
	static final class PipelineCompile extends Event {
		String pipeline, variant;
		boolean success;
		double translationMs, libraryMs, stateMs;
	}

	static PipelineCompile pipelineBegin(String name, String variant) {
		if (!ENABLED) return null;
		PipelineCompile event = new PipelineCompile();
		if (!event.isEnabled()) return null;
		event.pipeline = name; event.variant = variant; event.begin();
		return event;
	}

	static void pipelineEnd(PipelineCompile event, boolean success, long translation, long library, long state) {
		if (event == null) return;
		event.end(); event.success = success;
		event.translationMs = translation / 1e6; event.libraryMs = library / 1e6; event.stateMs = state / 1e6;
		event.commit();
	}

	@Name("mcopt.MetalGpuPass")
	@Label("Metal GPU pass")
	@Category("mcopt")
	@StackTrace(false)
	static final class GpuPass extends Event {
		long submit;
		int group;
		String label;
		double vertexMicros, fragmentMicros, startMicros, endMicros;
	}

	private static final jdk.jfr.EventType GPU_PASS_TYPE = jdk.jfr.EventType.getEventType(GpuPass.class);
	static boolean gpuPassEnabled() { return GPU_PASS_TYPE.isEnabled(); }

	/** Stage intervals may overlap; these are GPU timestamps, not CPU event durations or additive frame busy times. */
	static void gpuPass(long submit, int group, String label, double vs, double ve, double fs, double fe) {
		if (!gpuPassEnabled()) return;
		GpuPass event = new GpuPass();
		event.submit = submit; event.group = group; event.label = label;
		event.vertexMicros = vs >= 0 && ve >= vs ? ve - vs : -1;
		event.fragmentMicros = fs >= 0 && fe >= fs ? fe - fs : -1;
		event.startMicros = vs >= 0 && fs >= 0 ? Math.min(vs, fs) : Math.max(vs, fs);
		event.endMicros = Math.max(ve, fe);
		event.commit();
	}

	@Name("mcopt.MetalOperation")
	@Label("Metal operation")
	@Category("mcopt")
	@Threshold("1 ms")
	@StackTrace(true)
	static final class Operation extends Event {
		@Label("Operation") String operation;
		@Label("Submit") long submit;
		@Label("Bytes") @jdk.jfr.DataAmount long bytes;
		@Label("Arena") boolean arena;
		@Label("Main command buffer GPU microseconds") double gpuMicros;
		@Label("Unretired submissions") int pendingFrames;
		/** Native host seconds bracket just nextDrawable, excluding Java downcall/safepoint delays. */
		double nativeStart, nativeEnd;
		/** Includes driver work; excludes time blocked or descheduled. -1 means unavailable. */
		double nativeThreadCpuMs;
		/** Bounded acquisition: worker's actual driver wait can span several render frames. */
		double drawableWorkerStart, drawableWorkerEnd, drawableWorkerCpuMs;
		int drawableStatus;
		boolean boundedDrawable;
		long surfaceLayer, surfaceGeneration;
		int surfaceWidth, surfaceHeight;
	}

	static long nextDrawableBounded(long state, long waitNs, Operation event) {
		if (event == null) return Native.drawableAcquireNext(state, waitNs, 0);
		event.boundedDrawable = true;
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, 56);
			long drawable = Native.drawableAcquireNext(state, waitNs, out);
			event.nativeStart = org.lwjgl.system.MemoryUtil.memGetDouble(out);
			event.nativeEnd = org.lwjgl.system.MemoryUtil.memGetDouble(out + 8);
			event.nativeThreadCpuMs = org.lwjgl.system.MemoryUtil.memGetDouble(out + 16);
			event.drawableWorkerStart = org.lwjgl.system.MemoryUtil.memGetDouble(out + 24);
			event.drawableWorkerEnd = org.lwjgl.system.MemoryUtil.memGetDouble(out + 32);
			event.drawableWorkerCpuMs = org.lwjgl.system.MemoryUtil.memGetDouble(out + 40);
			event.drawableStatus = (int) org.lwjgl.system.MemoryUtil.memGetDouble(out + 48);
			return drawable;
		}
	}

	static long nextDrawable(long layer, Operation event) {
		if (event == null) return Native.layerNext(layer);
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, 24);
			long drawable = Native.layerNextTimed(layer, out);
			event.nativeStart = org.lwjgl.system.MemoryUtil.memGetDouble(out);
			event.nativeEnd = org.lwjgl.system.MemoryUtil.memGetDouble(out + 8);
			event.nativeThreadCpuMs = org.lwjgl.system.MemoryUtil.memGetDouble(out + 16);
			return drawable;
		}
	}

	static Operation begin(String operation, long submit, long bytes) {
		if (!ENABLED) return null;
		Operation event = new Operation();
		if (!event.isEnabled()) return null;
		event.operation = operation;
		event.submit = submit;
		event.bytes = bytes;
		event.begin();
		return event;
	}

	@Name("mcopt.MetalArenaTransfer")
	@Label("Metal arena transfer")
	@Category("mcopt")
	@StackTrace(true)
	static final class ArenaTransfer extends Event {
		String operation;
		@jdk.jfr.DataAmount long bytes;
		@jdk.jfr.DataAmount long sourceCapacity;
		@jdk.jfr.DataAmount long destinationCapacity;
	}

	/** Fast-to-encode copies can still cause a later GPU wait. Keep their sizes even below the operation threshold. */
	static void arenaTransfer(String operation, long bytes, long sourceCapacity, long destinationCapacity) {
		if (!ENABLED) return;
		ArenaTransfer event = new ArenaTransfer();
		if (!event.isEnabled()) return;
		event.operation = operation;
		event.bytes = bytes;
		event.sourceCapacity = sourceCapacity;
		event.destinationCapacity = destinationCapacity;
		event.commit();
	}

	static void end(Operation event) {
		if (event == null) return;
		event.end();
		event.commit();
	}
}
