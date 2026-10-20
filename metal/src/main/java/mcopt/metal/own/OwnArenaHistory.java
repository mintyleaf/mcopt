package mcopt.metal.own;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Capacity hints only, never terrain data. Read at renderer creation and save at dispatcher disposal. */
final class OwnArenaHistory {
	private static final int MAX_ENTRIES = 32;
	private final Path file;
	private final String key;
	private final Properties entries;

	OwnArenaHistory(Path file, int renderDistance, int quadBytes, boolean sorted) {
		this.file = file;
		this.key = key(renderDistance, quadBytes, sorted);
		this.entries = read(file);
	}

	static String key(int renderDistance, int quadBytes, boolean sorted) {
		return "v1.rd" + renderDistance + ".q" + quadBytes + ".sorted" + (sorted ? 1 : 0);
	}

	int units() { return units(this.entries.getProperty(this.key)); }

	private static int units(String value) {
		try {
			int n = Integer.parseInt(value);
			return n >= OwnArenaSizing.LOGICAL_INITIAL_UNITS && n <= 64 << 20 ? n : 0;
		} catch (NumberFormatException ignored) { return 0; }
	}

	private static Properties read(Path file) {
		Properties result = new Properties();
		try {
			if (Files.isRegularFile(file) && Files.size(file) <= 16384) {
				try (var in = Files.newBufferedReader(file)) { result.load(in); }
			}
		} catch (IOException | IllegalArgumentException ignored) { result.clear(); }
		return result;
	}

	/** Called after GPU idle and terrain disposal, never on allocation/growth or each frame. */
	void save(int observedUnits) {
		if (units(Integer.toString(observedUnits)) == 0 || observedUnits <= units()) return;
		Path temp = null;
		try {
			Properties latest = read(this.file);
			// Keep a bounded set of valid hints, and preserve a larger hint from another disposal.
			Properties next = new Properties();
			latest.stringPropertyNames().stream().filter(k -> k.matches("v1\\.rd[0-9]+\\.q[0-9]+\\.sorted[01]") && units(latest.getProperty(k)) > 0)
				.sorted().limit(MAX_ENTRIES - 1).forEach(k -> next.setProperty(k, latest.getProperty(k)));
			next.setProperty(this.key, Integer.toString(Math.max(observedUnits, units(latest.getProperty(this.key)))));
			Files.createDirectories(this.file.toAbsolutePath().getParent());
			temp = Files.createTempFile(this.file.toAbsolutePath().getParent(), "terrain-arena-", ".tmp");
			try (var out = Files.newBufferedWriter(temp)) { next.store(out, "mcopt terrain arena capacity hints; units are quads, not bytes. Safe to delete."); }
			try { Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
			catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING); }
			this.entries.clear(); this.entries.putAll(next);
		} catch (IOException | IllegalArgumentException e) {
			System.out.println("mcopt-own: could not save arena capacity hint: " + e);
		} finally {
			if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
		}
	}
}
