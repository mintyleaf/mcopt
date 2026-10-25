package mcopt.metal.lod;

import java.util.concurrent.ConcurrentHashMap;

/** Coordinate-only capture tokens. Invalidated by edits, light changes, reloads and unloads; never retain live chunks. */
final class LodCaptureFreshness {
	private static final boolean MIX_KEYS = Boolean.parseBoolean(System.getProperty("mcopt.lod.captureHash", "true"));
	static final class Token { volatile boolean ready; }
	private final ConcurrentHashMap<Long, Token> tokens = new ConcurrentHashMap<>();
	long reusedEdits;

	Token begin(long key) {
		var token = new Token();
		this.tokens.put(mapKey(key), token);
		return token;
	}
	void invalidate(long key) { this.tokens.remove(mapKey(key)); }
	void clear() { this.tokens.clear(); }
	/** Taking a token also retires its coordinate. A late completion cannot reinsert it. */
	boolean takeReady(long key) {
		var token = this.tokens.remove(mapKey(key));
		return token != null && token.ready;
	}
	int size() { return this.tokens.size(); }

	/** A completed capture since the last invalidation already includes an older queued edit. */
	Long pollEdited(java.util.concurrent.ConcurrentHashMap<Long, Long> edits, long now, long quiet) {
		for (var e : edits.entrySet()) {
			if (now - e.getValue() < quiet) continue;
			long key = e.getKey();
			if (!edits.remove(key, e.getValue())) continue;
			Token token = this.tokens.get(mapKey(key));
			if (token != null && token.ready) { this.reusedEdits++; continue; }
			return key;
		}
		return null;
	}

	/** Bijective 64-bit avalanche: packed nearby chunk coordinates otherwise hash to x^z. */
	static long mixedKey(long key) {
		key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
		key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
		return key ^ (key >>> 31);
	}
	private static long mapKey(long key) { return MIX_KEYS ? mixedKey(key) : key; }
}
