package mcopt.metal.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Atomic live controls. Each world worker keeps the immutable snapshot it was created with. */
public final class McoptRuntime {
	private static final Set<String> LIVE = Set.of("mcopt.lod", "mcopt.lod.radius", "mcopt.lod.refine",
		"mcopt.lod.refineRadius", "mcopt.lod.scoutRadius", "mcopt.lod.workload", "mcopt.lod.refineIntervalMs",
		"mcopt.lod.importSaved", "mcopt.lod.importRadius", "mcopt.lod.threads", "mcopt.lod.altitudeDetail",
		"mcopt.lod.altitudeStart", "mcopt.lod.altitudeRange", "mcopt.lod.altitudeMinPercent", "mcopt.lod.altitudePixels",
		"mcopt.lod.haze", "mcopt.lod.debugHud");
	private static final Set<String> VISUAL = Set.of("mcopt.lod.altitudeDetail", "mcopt.lod.altitudeStart",
		"mcopt.lod.altitudeRange", "mcopt.lod.altitudeMinPercent", "mcopt.lod.altitudePixels", "mcopt.lod.haze", "mcopt.lod.debugHud");
	public record State(long generation, Map<String, String> values) {
		public State { values = Map.copyOf(values); }
		public boolean flag(String key) { return Boolean.parseBoolean(values.get(key)); }
		public int integer(String key) { return Integer.parseInt(values.get(key)); }
		public double number(String key) { return Double.parseDouble(values.get(key)); }
		public String value(String key) { return values.get(key); }
	}
	private static volatile State current = initial();
	private McoptRuntime() { }
	public static State current() { return current; }
	public static boolean live(String key) { return LIVE.contains(key); }
	private static State initial() {
		Map<String, String> values = new LinkedHashMap<>();
		for (var option : McoptSettings.OPTIONS) if (live(option.key())) {
			String fallback = option.fallback();
			if (option.key().equals("mcopt.lod") && System.getProperty("mcopt.lod.radius") != null) fallback = "true";
			String value = System.getProperty(option.key(), fallback);
			try { value = option.validate(value); }
			catch (IllegalArgumentException ignored) { value = option.validate(fallback); }
			values.put(option.key(), value);
		}
		return new State(0, values);
	}
	/** Validate every live edit before publishing any of them. Startup-only keys are left to the saved config. */
	public static synchronized void apply(Map<String, String> edits) {
		var values = new LinkedHashMap<>(current.values());
		for (var option : McoptSettings.OPTIONS) if (live(option.key()) && edits.containsKey(option.key()))
			values.put(option.key(), option.validate(edits.get(option.key()).trim()));
		if (values.equals(current.values())) return;
		boolean rebuild = values.entrySet().stream().anyMatch(e -> !VISUAL.contains(e.getKey()) && !e.getValue().equals(current.value(e.getKey())));
		current = new State(current.generation() + (rebuild ? 1 : 0), values);
	}
}
