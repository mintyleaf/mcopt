package mcopt.metal.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Saves edited keys atomically, then publishes the supported live controls. */
final class McoptSettings {
	record Option(String key, String label, String fallback, double min, double max, String hint, String... choices) {
		boolean live() { return McoptRuntime.live(key); }
		private boolean fractional() { return key.endsWith("haze") || key.equals("mcopt.lod.altitudePixels"); }
		String validate(String value) {
			if (choices.length > 0) {
				for (String c : choices) if (c.equals(value)) return value;
				throw new IllegalArgumentException(label + ": choose a listed value");
			}
			try {
				double n = Double.parseDouble(value);
				if (Double.isFinite(n) && n >= min && n <= max && (fractional() || n == Math.rint(n)))
					return fractional() ? Double.toString(n) : Long.toString((long)n);
			} catch (NumberFormatException ignored) { }
			throw new IllegalArgumentException(label + ": expected " + min + " to " + max);
		}
	}
	static final Option[] OPTIONS = {
		bool("mcopt.lod", "Distant terrain", "false", "Enable mcopt LOD terrain. Turning it off also stops LOD background work and releases world buffers."),
		number("mcopt.lod.radius", "LOD distance (chunks)", "512", 32, 2048, "Terrain visibility distance. This does not increase structure scouting distance."),
		bool("mcopt.lod.textures", "LOD block textures", "true", "Draw block textures on the finest LOD level."),
		bool("mcopt.lod.waterBlend", "Blend LOD water colors", "true", "Smooth colors between neighboring water cells at the same height. Keeps land, roof colors, water geometry and lighting separate. Applies to existing caches."),
		bool("mcopt.lod.reuseVisibility", "Reuse stationary LOD visibility", "true", "Reuse GPU visibility results while camera, projection, geometry and near coverage are unchanged. Movement or changes rebuild them immediately."),
		number("mcopt.lod.aoPixels", "LOD AO cutoff (pixels)", "2", 0, 8, "Fade fine ambient occlusion below twice this projected block size; skip it below the cutoff. Lighting and textures remain. 0 keeps full fine AO."),
		bool("mcopt.lod.altitudeDetail", "Height-aware LOD detail", "true", "Gradually use coarser terrain while flying high. Keeps the horizon, nearby terrain and zoom detail."),
		number("mcopt.lod.altitudeStart", "Height reduction starts (blocks)", "32", 0, 1024, "Clearance above nearby loaded terrain before detail starts decreasing. This is not absolute world Y."),
		number("mcopt.lod.altitudeRange", "Height reduction span (blocks)", "160", 1, 1024, "Additional clearance to reach full reduction. With defaults, reduction runs from 32 to 192 blocks above terrain."),
		number("mcopt.lod.altitudeMinPercent", "Minimum detail radius (%)", "25", 25, 100, "Lower values permit coarser terrain at altitude. 25 permits two tiers of reduction; 100 disables reduction. Zoom and the full-terrain boundary can require more detail."),
		number("mcopt.lod.altitudePixels", "Height detail pixel tolerance", "6", 1, 16, "Higher values allow larger distant cells on screen and stronger height reduction. Uses actual rendering resolution. Lower values preserve more detail, especially when zoomed."),
		bool("mcopt.compat.litematicaOverlayBatch", "Batch Litematica overlays", "true", "Combine compatible colored schematic overlay draws. Supports Litematica 0.29.1. Requires restarting Minecraft."),
		bool("mcopt.lod.lighting", "Captured lighting", "true", "Preserve sky and block light from populated chunks."),
		bool("mcopt.lod.overhangs", "Vertical layers", "true", "Preserve air gaps and floating surfaces from populated terrain."),
		bool("mcopt.lod.waterTransition", "Water transition", "true", "Blend full terrain water into distant water."),
		number("mcopt.lod.handoffMs", "Terrain transition (ms)", "200", 0, 2000, "Time to dissolve overlapping LODs when full terrain arrives."),
		number("mcopt.lod.haze", "Vanilla haze scale", "1.0", 0.25, 4, "Distant vanilla fog range multiplier."),
		bool("mcopt.lod.refine", "Population and scouting", "true", "Singleplayer: progressively generate actual structures, vegetation and light."),
		number("mcopt.lod.refineRadius", "Population radius (chunks)", "32", 0, 64, "FULL generation near the player. 0 disables new generation, including scouts."),
		number("mcopt.lod.scoutRadius", "Structure radius (chunks)", "256", 0, 512, "Search for structures within this radius. Nether scouting is capped at 64 chunks."),
		new Option("mcopt.lod.workload", "Population workload", "balanced", 0, 0, "Adaptive population limits: low 1 FULL/1 probe; balanced 2/2; high 4/3. Separate from renderer optimization profiles.", "low", "balanced", "high"),
		number("mcopt.lod.refineIntervalMs", "FULL start spacing (ms)", "0", 0, 60000, "Optional minimum spacing between FULL starts. 0 uses adaptive limits; no delay is added after completion. Probes are paced separately."),
		bool("mcopt.lod.importSaved", "Import saved terrain", "true", "Import populated chunks already saved in this world."),
		number("mcopt.lod.importRadius", "Import radius (chunks)", "128", 0, 512, "Read saved chunks within this radius, capped to visible LOD reach (Nether: 64 chunks). Imports do not generate unexplored terrain."),
		bool("mcopt.lod.structureDetail", "Structure detail", "true", "Keep vertical layers and lighting across distant detail levels."),
		bool("mcopt.lod.cache", "Disk cache", "true", "Reuse per-world LOD terrain between sessions."),
		bool("mcopt.lod.small", "Small GPU mode", "false", "Reduce clipmap memory and geometry. Explicit advanced clipmap settings take precedence."),
		number("mcopt.lod.threads", "LOD worker threads", Integer.toString(Math.clamp(Runtime.getRuntime().availableProcessors() - 4, 2, 16)), 1, 16, "Background estimated-terrain workers; separate from vanilla FULL generation."),
		bool("mcopt.lod.trees", "Estimated trees", "true", "Approximate vegetation before populated terrain is available."),
		bool("mcopt.lod.plants", "Small plants", "true", "Draw small plants on textured level-0 LODs."),
		bool("mcopt.lod.debugHud", "F3 LOD statistics", "true", "Show tile queues, structure progress and reasons for waiting in F3."),
		bool("mcopt.lod.memoryBudget", "LOD heap headroom", "true", "Pause optional estimated terrain, cached tile decoding, scouting and saved imports near the heap limit. Accepted visited captures continue draining. Resumes when headroom recovers."),
		bool("mcopt.lod.yield.streaming", "LOD progress during flight", "true", "Share one generation slot between fallback terrain and visible LODs ahead while chunks stream in. Nearby holes or a large chunk backlog pause detailed work. Applies to missing/spare CPU admission modes."),
		new Option("profile", "Optimization profile", "indie", 0, 0, "Startup optimization profile. Individual overrides still take precedence.", "indie", "recommended", "alpha", "none"),
		bool("mcopt.metal", "Metal renderer", "true", "Native Apple Metal backend. Requires restarting Minecraft."),
		bool("mcopt.own", "mcopt terrain renderer", "true", "Use mcopt's full terrain renderer with the Metal backend."),
		bool("mcopt.own.arena.remember", "Remember terrain buffer size", "true", "Reuse measured buffer capacity for the same render distance and vertex format on the next launch. Saved when terrain is unloaded."),
		number("mcopt.own.arena.reserveMaxMiB", "Startup buffer cap (MiB)", "512", 64, 2048, "Maximum initial terrain buffer reservation. Does not cap runtime terrain or change render distance. Higher values can increase memory pressure."),
		number("mcopt.own.arena.initialMiB", "Initial terrain buffer (MiB)", "0", 0, 2048, "0 uses remembered capacity. An explicit size overrides that hint, subject to the startup cap. Takes effect on the next launch.")
	};
	private static Option bool(String key, String label, String value, String hint) { return new Option(key, label, value, 0, 0, hint, "true", "false"); }
	private static Option number(String key, String label, String value, double min, double max, String hint) { return new Option(key, label, value, min, max, hint); }
	final Path path;
	final Map<String, String> values = new LinkedHashMap<>(), original = new LinkedHashMap<>();

	McoptSettings(Path path) throws IOException {
		this(path, false);
	}
	McoptSettings(Path path, boolean showLiveValues) throws IOException {
		this.path = path;
		Properties file = read(path);
		for (Option o : OPTIONS) {
			String effective = System.getProperty(o.key.equals("profile") ? "mcopt.profile" : o.key, o.fallback);
			if (o.key.equals("mcopt.lod") && System.getProperty(o.key) == null && System.getProperty("mcopt.lod.radius") != null) effective = "true";
			String value = file.getProperty(o.key, effective);
			if (showLiveValues && o.live()) value = McoptRuntime.current().value(o.key);
			if (o.key.equals("profile") && value.isBlank()) value = "indie";
			values.put(o.key, value); original.put(o.key, value);
		}
	}

	boolean changed() { return !values.equals(original); }

	void save() throws IOException {
		Map<String, String> edits = new LinkedHashMap<>();
		for (Option o : OPTIONS) if (!values.get(o.key).equals(original.get(o.key))) edits.put(o.key, o.validate(values.get(o.key).trim()));
		if (edits.isEmpty()) return;
		if (edits.containsKey("mcopt.lod.radius")) for (Option o : OPTIONS) if (o.key.equals("mcopt.lod"))
			edits.putIfAbsent(o.key, o.validate(values.get(o.key).trim()));
		Properties file = read(path); // Preserve unrelated keys, including edits made while the screen was open.
		edits.forEach(file::setProperty);
		Files.createDirectories(path.toAbsolutePath().getParent());
		Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "mcopt-settings-", ".tmp");
		try {
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { file.store(out, "mcopt settings. Live controls apply on save; other settings require a restart. JVM arguments take precedence at startup."); }
			try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
			catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
		} finally { Files.deleteIfExists(temp); }
		McoptRuntime.apply(edits);
	}

	private static Properties read(Path path) throws IOException {
		Properties result = new Properties();
		if (Files.exists(path)) try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(in); }
		return result;
	}
}
