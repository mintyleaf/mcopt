package mcopt.metal.own;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persisted hints are bounded, disposable and isolated by the settings that change mesh memory needs. */
public final class OwnArenaSizingChecks {
	public static void main(String[] args) throws Exception {
		int base = OwnArenaSizing.LOGICAL_INITIAL_UNITS;
		if (OwnArenaSizing.reserveUnits(base, 8 << 20, 0, 512, 64) != 8 << 20)
			throw new AssertionError("RD12/8-core learned capacity still gated by the legacy policy");
		if (OwnArenaSizing.reserveUnits(base, 32 << 20, 0, 512, 64) != 8 << 20
			|| OwnArenaSizing.reserveUnits(base, 8 << 20, 128, 512, 64) != 2 << 20
			|| OwnArenaSizing.reserveUnits(base, 8 << 20, 1024, 256, 64) != 4 << 20)
			throw new AssertionError("Startup cap or manual override ignored");
		if (OwnArenaSizing.reserveUnits(base, 0, 0, 512, 64) != base
			|| OwnArenaSizing.reserveUnits(base, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 64) != 32 << 20
			|| OwnArenaSizing.reserveUnits(base, 0, 1, 64, 112) != base)
			throw new AssertionError("Unmeasured settings, overflow bounds or minimum noncompact arena changed");
		Path directory = Files.createTempDirectory("mcopt-arena-sizing-");
		try {
			Path file = directory.resolve("cache/hints.properties");
			var history = new OwnArenaHistory(file, 12, 64, true);
			if (history.units() != 0 || Files.exists(file)) throw new AssertionError("Missing history created or guessed a hint");
			history.save(8 << 20);
			if (new OwnArenaHistory(file, 12, 64, true).units() != 8 << 20
				|| new OwnArenaHistory(file, 16, 64, true).units() != 0
				|| new OwnArenaHistory(file, 12, 112, true).units() != 0
				|| new OwnArenaHistory(file, 12, 64, false).units() != 0)
				throw new AssertionError("History lost or leaked across distance/vertex/sorting profiles");
			byte[] before = Files.readAllBytes(file);
			history.save(base);
			if (!java.util.Arrays.equals(before, Files.readAllBytes(file))) throw new AssertionError("Smaller disposal rewrote the high-water hint");
			// A second profile survives a later update from an object created before that profile was saved.
			var other = new OwnArenaHistory(file, 16, 64, true); other.save(4 << 20);
			history.save(16 << 20);
			if (new OwnArenaHistory(file, 16, 64, true).units() != 4 << 20) throw new AssertionError("Save lost another profile");
			String key = OwnArenaHistory.key(12, 64, true);
			for (String invalid : new String[]{"-1", "2147483647", "not-a-number", "1"}) {
				Files.writeString(file, key + "=" + invalid + "\n");
				if (new OwnArenaHistory(file, 12, 64, true).units() != 0) throw new AssertionError("Invalid cache capacity accepted");
			}
			Files.writeString(file, "broken=\\uZZZZ\n");
			if (new OwnArenaHistory(file, 12, 64, true).units() != 0) throw new AssertionError("Corrupt history accepted");
			Files.writeString(file, "x".repeat(20000));
			if (new OwnArenaHistory(file, 12, 64, true).units() != 0) throw new AssertionError("Oversized history accepted");
			var crowded = new Properties();
			for (int i = 1; i <= 100; i++) crowded.setProperty(OwnArenaHistory.key(i, 64, true), Integer.toString(base));
			try (var out = Files.newBufferedWriter(file)) { crowded.store(out, "fixture"); }
			new OwnArenaHistory(file, 12, 64, true).save(8 << 20);
			var bounded = new Properties(); try (var in = Files.newBufferedReader(file)) { bounded.load(in); }
			if (bounded.size() > 32 || new OwnArenaHistory(file, 12, 64, true).units() != 8 << 20)
				throw new AssertionError("History did not stay bounded or lost its current profile");
			// A cache write failure cannot prevent playing or change the previous readable cache.
			Path blocked = directory.resolve("not-a-directory"); Files.writeString(blocked, "fixture");
			new OwnArenaHistory(blocked.resolve("hints"), 12, 64, true).save(8 << 20);
			try (var paths = Files.list(file.getParent())) {
				if (paths.count() != 1) throw new AssertionError("Temporary history files leaked");
			}
			System.out.println("PASS: learned/manual startup reservation, caps, absent/corrupt/oversized hints, profile isolation, bounded atomic history and nonfatal write failures");
		} finally {
			try (var paths = Files.walk(directory)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
			}
		}
	}
}
