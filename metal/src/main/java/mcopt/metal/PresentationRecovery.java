package mcopt.metal;

/** Opt-in recovery after an actual bounded acquisition failure; ordinary frames never wait here. */
public final class PresentationRecovery {
	private PresentationRecovery() { }
	public static boolean beginFrame(boolean eligible) { return MetalSurface.deferRendering(eligible); }
	public static long skippedFrames() { return MetalSurface.recoverySkippedFrames(); }
}
