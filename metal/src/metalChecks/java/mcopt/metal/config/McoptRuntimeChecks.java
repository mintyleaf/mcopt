package mcopt.metal.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Publication and persistence boundaries used by the actual in-game editor. */
public final class McoptRuntimeChecks {
	public static void main(String[] args) throws Exception {
		var initial = McoptRuntime.current();
		Path dir = Files.createTempDirectory("mcopt-live-check-");
		try {
			McoptRuntime.apply(Map.of("mcopt.lod", "false", "mcopt.lod.radius", "128", "mcopt.lod.scoutRadius", "64"));
			var off = McoptRuntime.current();
			if (off.flag("mcopt.lod") || mcopt.metal.own.OwnViewDistance.verticalDistance(12) != 12) throw new AssertionError("live off did not restore vertical distance");
			try { McoptRuntime.apply(Map.of("mcopt.lod", "true", "mcopt.lod.radius", "NaN")); throw new AssertionError("invalid edit accepted"); }
			catch (IllegalArgumentException expected) { }
			if (McoptRuntime.current() != off) throw new AssertionError("partial live edit published");
			McoptRuntime.apply(Map.of("mcopt.lod", "true", "mcopt.lod.workload", "low", "mcopt.lod.threads", "1"));
			var on = McoptRuntime.current();
			if (!on.flag("mcopt.lod") || off.flag("mcopt.lod") || on.generation() <= off.generation()
				|| mcopt.metal.own.OwnViewDistance.verticalDistance(12) != Integer.MAX_VALUE) throw new AssertionError("off/on snapshots disagree");
			try { on.values().put("mcopt.lod", "false"); throw new AssertionError("mutable snapshot"); }
			catch (UnsupportedOperationException expected) { }
			McoptRuntime.apply(Map.of("mcopt.lod.haze", "1.5", "mcopt.lod.altitudeDetail", "false"));
			if (McoptRuntime.current().generation() != on.generation()) throw new AssertionError("visual edit restarted world");
			var visual = McoptRuntime.current();
			McoptRuntime.apply(Map.of("mcopt.lod.small", "true", "profile", "none"));
			if (McoptRuntime.current() != visual) throw new AssertionError("startup-only edit applied live");
			Path file = dir.resolve("mcopt.properties");
			Files.writeString(file, "mcopt.lod=false\nmcopt.lod.radius=512\ncustom=preserved\n");
			var editor = new McoptSettings(file, true);
			if (!editor.values.get("mcopt.lod").equals("true") || !editor.values.get("mcopt.lod.radius").equals("128")) throw new AssertionError("editor did not show effective live values");
			editor.values.put("mcopt.lod.radius", "96"); editor.save();
			if (McoptRuntime.current().integer("mcopt.lod.radius") != 96 || !Files.readString(file).contains("custom=preserved")) throw new AssertionError("save did not apply/preserve settings");
			// A failed atomic replacement must leave the runtime unchanged.
			var badEditor = new McoptSettings(dir.resolve("missing/config.properties"));
			Files.writeString(dir.resolve("missing"), "not a directory");
			badEditor.values.put("mcopt.lod.radius", "160");
			var before = McoptRuntime.current();
			try { badEditor.save(); throw new AssertionError("failed save accepted"); } catch (java.io.IOException expected) { }
			if (McoptRuntime.current() != before) throw new AssertionError("failed save published live edits");
			McoptRuntime.apply(Map.of("mcopt.lod.radius", "128", "mcopt.lod.scoutRadius", "64"));
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread reader = Thread.ofPlatform().start(() -> {
				try { for (int i = 0; i < 200_000; i++) {
					var s = McoptRuntime.current(); int r = s.integer("mcopt.lod.radius"), scout = s.integer("mcopt.lod.scoutRadius");
					if (!((r == 128 && scout == 64) || (r == 256 && scout == 128))) throw new AssertionError("torn live snapshot");
				} } catch (Throwable e) { failure.set(e); }
			});
			for (int i = 0; i < 2000; i++) McoptRuntime.apply(i % 2 == 0
				? Map.of("mcopt.lod.radius", "256", "mcopt.lod.scoutRadius", "128") : Map.of("mcopt.lod.radius", "128", "mcopt.lod.scoutRadius", "64"));
			reader.join(); if (failure.get() != null) throw new AssertionError(failure.get());
			System.out.println("PASS: live off/on, immutable atomic snapshots, whole-edit validation, effective UI values, startup-only isolation and save-failure rollback");
		} finally {
			McoptRuntime.apply(initial.values());
			try (var paths = Files.walk(dir)) { for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
		}
	}
}
