package mcopt.metal.lod;

import mcopt.metal.config.McoptRuntime;

/** Bounded render-only quality bias. Unknown ground, proximity and zoom always favor detail. */
final class LodAltitudeQuality {
	private double continuous = 1, demand = 1;
	private float scale = 1;
	private long last;
	private McoptRuntime.State settings;
	private double pixels, min;
	private int start, range;
	private boolean enabled;
	@jdk.jfr.Name("mcopt.LodDetail") @jdk.jfr.Label("LOD render detail") @jdk.jfr.Category("mcopt") @jdk.jfr.StackTrace(false)
	static final class Sample extends jdk.jfr.Event { float scale, fineRadius; double clearance, pixelsPerRadian; }
	static void record(float scale, float radius, double clearance, double pixels) {
		if (!Boolean.getBoolean("mcopt.metal.jfr")) return;
		Sample s = new Sample();
		if (s.isEnabled()) {s.scale=scale;s.fineRadius=radius;s.clearance=clearance;s.pixelsPerRadian=pixels;s.commit();}
	}

	float update(double cameraY, double surfaceY, double pixelsPerRadian, float fineRadius, float protectedRadius, long now) {
		var settings = McoptRuntime.current();
		// Visual edits publish a new snapshot without a world generation change. Parse only on publication.
		if (settings != this.settings) {
			this.pixels = settings.number("mcopt.lod.altitudePixels");
			this.min = settings.integer("mcopt.lod.altitudeMinPercent") / 100.0;
			this.start = settings.integer("mcopt.lod.altitudeStart");
			this.range = settings.integer("mcopt.lod.altitudeRange");
			this.enabled = settings.flag("mcopt.lod.altitudeDetail");
			this.settings = settings;
		}
		double target = target(cameraY, surfaceY, pixelsPerRadian, fineRadius, protectedRadius, pixels,
			start, range, min);
		if (!Double.isFinite(surfaceY) || !enabled) {
			reset(); this.last = now; return this.scale;
		}
		double dt = this.last == 0 ? 0 : Math.clamp((now - this.last) / 1e9, 0, .25);
		this.last = now;
		// Hysteresis avoids rebuilding position-keyed lists for tiny camera/heightmap changes.
		if (Math.abs(target - this.demand) >= 1.0 / 128 || target == 1) this.demand = target;
		this.continuous += Math.clamp(this.demand - this.continuous, -dt * .25, dt * .5);
		// Pixel and handoff limits are safety floors: zooming or increasing RD restores them immediately.
		double floor = floor(pixelsPerRadian, fineRadius, protectedRadius, pixels, min);
		this.continuous = Math.max(this.continuous, floor);
		this.scale = (float) Math.clamp(Math.ceil(this.continuous * 128) / 128, min, 1);
		return this.scale;
	}

	static double target(double cameraY, double surfaceY, double pixels, float radius, float protect, double pixelLimit,
		double start, double range, double min) {
		if (!Double.isFinite(cameraY) || !Double.isFinite(surfaceY)) return 1;
		double t = Math.clamp((cameraY - surfaceY - start) / range, 0, 1);
		t = t * t * (3 - 2 * t);
		return Math.max(1 - (1 - min) * t, floor(pixels, radius, protect, pixelLimit, min));
	}

	private static double floor(double pixels, float radius, float protect, double pixelLimit, double min) {
		if (!(radius > 0) || !Double.isFinite(pixels) || pixels <= 0 || !Double.isFinite(pixelLimit) || pixelLimit <= 0) return 1;
		return Math.clamp(Math.max(protect, pixels / pixelLimit) / radius, min, 1);
	}

	void reset() { this.continuous = this.demand = this.scale = 1; this.last = 0; }
}
