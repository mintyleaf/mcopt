package mcopt.metal.lod;

import java.util.Map;
import mcopt.metal.config.McoptRuntime;

/** Clearance, live controls, projection/zoom floors, transition rate and world-reset checks. */
public final class LodAltitudeQualityChecks {
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
	private static double target(double y, double ground, double pixels, float protect) {
		return LodAltitudeQuality.target(y, ground, pixels, 956, protect, 6, 32, 160, .25);
	}
	public static void main(String[] args) {
		check(target(320, 300, 1156, 304) == 1, "Absolute build height reduced nearby mountain detail");
		check(target(96, 64, 1156, 304) == 1, "Near terrain detail changed");
		double handoff = 304.0 / 956;
		check(target(256, 64, 1156, 304) == handoff, "High flight did not reach the protected handoff");
		check(target(200, 64, 1156, 304) < .5, "Moderate altitude retained the old conservative reduction");
		check(target(256, 64, 1156, 128) == .25, "Two-tier reduction was not available");
		check(target(320, 64, 6046, 304) == 1, "Zoom lost pixel detail");
		check(target(320, 64, 1156, 1024) == 1, "Fine ring crossed the full terrain handoff");
		check(target(320, Double.NaN, 1156, 304) == 1, "Unknown ground reduced detail");
		check(target(320, 64, Double.NaN, 304) == 1, "Invalid projection reduced detail");
		check(LodAltitudeQuality.target(320,64,1156,956,304,3,64,192,.5)==.5, "Legacy settings cannot be reproduced");
		double previous = 1;
		for (int clearance = 0; clearance <= 1000; clearance++) {
			double value = target(clearance + 64, 64, 1156, 304);
			check(value >= handoff && value <= previous, "Unbounded/non-monotone altitude bias"); previous = value;
		}
		var initial=McoptRuntime.current();
		try {
			McoptRuntime.apply(Map.of("mcopt.lod.altitudeDetail","true", "mcopt.lod.altitudeStart","32",
				"mcopt.lod.altitudeRange","160", "mcopt.lod.altitudeMinPercent","25", "mcopt.lod.altitudePixels","6"));
			LodAltitudeQuality state = new LodAltitudeQuality(); long now = 1_000_000_000L;
			float last = 1;
			for (int frame = 0; frame < 240; frame++) {
				float value = state.update(320, 64, 1156, 956, 304, now += 16_666_667);
				check(last - value <= 1f/128 + .00001 && value <= last, "Coarsening jumped or oscillated"); last = value;
			}
			check(last == Math.ceil(handoff*128)/128, "Stationary high view did not settle");
			check(state.update(320,64,6046,956,304,now+=16_666_667)==1, "Zoom floor was delayed");
			for (int i=0;i<240;i++) state.update(320,64,1156,956,304,now+=16_666_667);
			long generation=McoptRuntime.current().generation();
			McoptRuntime.apply(Map.of("mcopt.lod.altitudePixels","1"));
			check(state.update(320,64,1156,956,304,now+=16_666_667)==1, "Live pixel tolerance did not restore detail immediately");
			McoptRuntime.apply(Map.of("mcopt.lod.altitudePixels","6", "mcopt.lod.altitudeMinPercent","100"));
			check(state.update(320,64,1156,956,304,now+=16_666_667)==1, "Live minimum radius was delayed");
			McoptRuntime.apply(Map.of("mcopt.lod.altitudeMinPercent","25", "mcopt.lod.altitudeStart","512", "mcopt.lod.altitudeRange","512"));
			for (int i=0;i<240;i++) check(state.update(320,64,1156,956,304,now+=16_666_667)==1, "Live clearance controls were ignored");
			check(McoptRuntime.current().generation()==generation, "Height controls restarted the world");
			McoptRuntime.apply(Map.of("mcopt.lod.altitudeStart","32", "mcopt.lod.altitudeRange","160"));
			for (int i=0;i<240;i++) state.update(320,64,1156,956,304,now+=16_666_667);
			check(state.update(320,Double.NaN,1156,956,304,now+=16_666_667)==1, "Missing/new world inherited reduced detail");
			state.reset();
			check(state.update(320,64,1156,956,304,now)==1, "Dimension reset retained old scale");
		} finally { McoptRuntime.apply(initial.values()); }
		System.out.println("PASS: stronger clearance reduction, live thresholds/minimum/pixel controls without world restart, zoom/handoff guards, rate limits and world reset");
	}
}
