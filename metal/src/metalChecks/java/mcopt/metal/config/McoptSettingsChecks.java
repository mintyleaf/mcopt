package mcopt.metal.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Exercises the same persistence model used by Mod Menu, in a disposable directory. */
public final class McoptSettingsChecks {
	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("mcopt-settings-check-");
		String threads = System.getProperty("mcopt.lod.threads");
		try {
			Path file = dir.resolve("mcopt.properties");
			Files.writeString(file, "profile=indie\nmcopt.lod=true\nmcopt.lod.radius=512\nmcopt.custom=value\n");
			System.setProperty("mcopt.lod.threads", "3");
			McoptSettings model = new McoptSettings(file);
			if (!model.values.get("mcopt.lod.threads").equals("3")) throw new AssertionError("effective JVM override not shown");
			byte[] before = Files.readAllBytes(file);
			model.save();
			if (!java.util.Arrays.equals(before, Files.readAllBytes(file))) throw new AssertionError("untouched screen rewrote settings");
			Files.writeString(file, Files.readString(file) + "mcopt.external=preserved\n");
			model.values.put("mcopt.lod.radius", "256.0");
			model.values.put("mcopt.lod", "false");
			model.save();
			Properties saved = read(file);
			if (!saved.getProperty("mcopt.lod.radius").equals("256") || !saved.getProperty("mcopt.lod").equals("false")) throw new AssertionError("radius/disable edit lost");
			if (!saved.getProperty("mcopt.custom").equals("value") || !saved.getProperty("mcopt.external").equals("preserved")) throw new AssertionError("unrelated or concurrent edit lost");
			if (saved.containsKey("mcopt.lod.threads")) throw new AssertionError("unedited effective JVM value was pinned to disk");
			model = new McoptSettings(file);
			for (String invalid : new String[]{"NaN", "Infinity", "-1", "5"}) {
				model.values.put("mcopt.lod.haze", invalid);
				before = Files.readAllBytes(file);
				try { model.save(); throw new AssertionError("invalid haze accepted: " + invalid); }
				catch (IllegalArgumentException expected) { }
				if (!java.util.Arrays.equals(before, Files.readAllBytes(file))) throw new AssertionError("validation failure changed file");
			}
			model.values.put("mcopt.lod.haze", "1.25"); model.save();
			if (!read(file).getProperty("mcopt.lod.haze").equals("1.25")) throw new AssertionError("fractional haze rejected");
			long generation=McoptRuntime.current().generation();
			model.values.put("mcopt.client.packetBudgetMs", "0");model.save();
			if(McoptRuntime.current().number("mcopt.client.packetBudgetMs")!=0 || McoptRuntime.current().generation()!=generation)throw new AssertionError("Packet live edit rebuilt LODs or did not apply");
			try (var files = Files.list(dir)) { if (files.count() != 1) throw new AssertionError("temporary files leaked"); }
			System.out.println("PASS: settings preserve unrelated/concurrent keys, validate before writing, canonicalize integers, leave JVM defaults unpinned and replace files atomically");
		} finally {
			if (threads == null) System.clearProperty("mcopt.lod.threads"); else System.setProperty("mcopt.lod.threads", threads);
			try (var files = Files.walk(dir)) { for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
		}
	}
	private static Properties read(Path path) throws Exception {
		Properties p = new Properties(); try (var in = Files.newBufferedReader(path)) { p.load(in); } return p;
	}
}
