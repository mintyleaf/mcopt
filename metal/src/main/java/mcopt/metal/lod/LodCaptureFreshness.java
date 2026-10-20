package mcopt.metal.lod;

import java.util.concurrent.ConcurrentHashMap;

/** Coordinate-only capture tokens. Invalidated by edits, light changes, reloads and unloads; never retain live chunks. */
final class LodCaptureFreshness {
	static final class Token { volatile boolean ready; }
	private final ConcurrentHashMap<Long, Token> tokens = new ConcurrentHashMap<>();
	long reusedEdits;

	Token begin(long key) {
		var token = new Token();
		this.tokens.put(key, token);
		return token;
	}
	void invalidate(long key) { this.tokens.remove(key); }
	void clear() { this.tokens.clear(); }
	/** Taking a token also retires its coordinate. A late completion cannot reinsert it. */
	boolean takeReady(long key) {
		var token = this.tokens.remove(key);
		return token != null && token.ready;
	}
	int size() { return this.tokens.size(); }

	/** A completed capture since the last invalidation already includes an older queued edit. */
	Long pollEdited(java.util.concurrent.ConcurrentHashMap<Long, Long> edits, long now, long quiet) {
		for (var e : edits.entrySet()) {
			if (now - e.getValue() < quiet) continue;
			long key = e.getKey();
			if (!edits.remove(key, e.getValue())) continue;
			Token token = this.tokens.get(key);
			if (token != null && token.ready) { this.reusedEdits++; continue; }
			return key;
		}
		return null;
	}
}
