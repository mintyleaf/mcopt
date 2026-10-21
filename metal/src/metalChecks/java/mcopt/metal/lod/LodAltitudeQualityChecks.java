package mcopt.metal.lod;

/** Clearance, projection/zoom floors, transition rate and world-reset checks. GPU tier ownership is tested separately. */
public final class LodAltitudeQualityChecks {
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
	private static double target(double y, double ground, double pixels, float protect) {
		return LodAltitudeQuality.target(y, ground, pixels, 956, protect, 3);
	}
	public static void main(String[] args) {
		check(target(320, 300, 1156, 304) == 1, "Absolute build height reduced nearby mountain detail");
		check(target(128, 64, 1156, 304) == 1, "Near terrain detail changed");
		check(target(320, 64, 1156, 304) == .5, "High flight did not select one coarser tier");
		check(target(200, 64, 1156, 304) < 1, "Moderate altitude had no effect");
		check(target(320, 64, 3023, 304) == 1, "Zoom lost pixel detail");
		check(target(320, 64, 1156, 1024) == 1, "Fine ring crossed the full terrain handoff");
		check(target(320, Double.NaN, 1156, 304) == 1, "Unknown ground reduced detail");
		check(target(320, 64, Double.NaN, 304) == 1, "Invalid projection reduced detail");
		double previous = 1;
		for (int clearance = 0; clearance <= 1000; clearance++) {
			double value = target(clearance + 64, 64, 1156, 304);
			check(value >= .5 && value <= previous, "Unbounded/non-monotone altitude bias"); previous = value;
		}
		LodAltitudeQuality state = new LodAltitudeQuality(); long now = 1_000_000_000L;
		float last = 1;
		for (int frame = 0; frame < 180; frame++) {
			float value = state.update(320, 64, 1156, 956, 304, now += 16_666_667);
			check(last - value <= 1f/128 + .00001 && value <= last, "Coarsening jumped or oscillated"); last = value;
		}
		check(last == .5, "Stationary high view did not settle");
		check(state.update(320,64,3023,956,304,now+=16_666_667)==1, "Zoom floor was delayed");
		for (int i=0;i<180;i++) state.update(320,64,1156,956,304,now+=16_666_667);
		check(state.update(320,Double.NaN,1156,956,304,now+=16_666_667)==1, "Missing/new world inherited reduced detail");
		state.reset();
		check(state.update(320,64,1156,956,304,now)==1, "Dimension reset retained old scale");
		System.out.println("PASS: clearance (not absolute Y), bounded coarsening, zoom/RD/projection guards, rate limits and world reset");
	}
}
