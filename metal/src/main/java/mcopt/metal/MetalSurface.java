package mcopt.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLVideo;

/**
 * A CAMetalLayer on the SDL window. The drawable is fetched at blit time rather than frame start: holding it for
 * the whole frame only adds latency and can stall on a drawable the display hasn't released yet.
 */
final class MetalSurface implements GpuSurfaceBackend {
	/** With vsync off, present only the last frame that makes each refresh (see mc_pace); -Dmcopt.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("mcopt.metal.pace", "true"));
	/** How long before a refresh a finished frame has to reach the compositor to be shown on it. */
	/** -Dmcopt.metal.paceMarginMs: how long before a refresh the paced present must be done (the compositor's latch); default 2. */
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("mcopt.metal.paceMarginMs", "2")) / 1000;
	/** -Dmcopt.metal.drawables=2|3: the layer's maximumDrawableCount (default 3). */
	private static final int DRAWABLES = Integer.getInteger("mcopt.metal.drawables", 0);
	/**
	 * -Dmcopt.metal.paceSync=true: the paced presents (vsync off) go out with display sync on, so each latches at a vblank. On
	 * the laptop's 120 Hz 5K display at ~1800 fps, paced presents without display sync reach the screen in pairs ~1.9 ms apart
	 * every 16.7 ms (a 60 Hz cadence); with vsync the grid is a clean 8.33 ms.
	 */
	private static final boolean PACE_SYNC = Boolean.getBoolean("mcopt.metal.paceSync");
	/**
	 * -Dmcopt.metal.paceAdapt=true: the pacer's margin learns the compositor's latch from scanout times (mc_pace in mcmetal.m): a
	 * paced frame shown more than half a refresh after the refresh it was aimed at adds lead, a frame on time takes a little away.
	 */
	static final boolean PACE_ADAPT = Boolean.getBoolean("mcopt.metal.paceAdapt");
	private boolean paced;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;
	private final long window;
	private static final boolean BOUNDED_DRAWABLE = Boolean.parseBoolean(System.getProperty("mcopt.metal.boundedDrawable", "true"));
	private final long drawableAcquire;
	private boolean vsync;
	private long generation;
	private int width, height;
	private long drawableWaitNanos = 22_000_000L;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.window = window;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
		this.drawableAcquire = BOUNDED_DRAWABLE && !MetalEncoder.PRESENT_ACQUIRE ? Native.drawableAcquireNew(this.layer) : 0;
		var mode = SDLVideo.SDL_GetCurrentDisplayMode(SDLVideo.SDL_GetDisplayForWindow(window));
		if (mode != null && mode.refresh_rate() > 0)
			this.drawableWaitNanos = drawableWaitNanos(mode.refresh_rate());
	}

	static long drawableWaitNanos(float refreshRate) {
		return (long) Math.clamp(1.25e9 / (refreshRate > 0 ? refreshRate : 60) + 1_000_000, 2_000_000, 60_000_000);
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		this.generation++;
		this.width = config.width(); this.height = config.height();
		var mode = SDLVideo.SDL_GetCurrentDisplayMode(SDLVideo.SDL_GetDisplayForWindow(this.window));
		this.drawableWaitNanos = drawableWaitNanos(mode == null ? 60 : mode.refresh_rate());
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		this.vsync = vsync;
		if (this.drawableAcquire != 0) Native.drawableAcquireInvalidate(this.drawableAcquire, config.width(), config.height());
		this.paced = !vsync && PACE;
		if (PACE_ADAPT) Native.paceAdapt(this.paced ? 1 : 0);
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (vsync || PACE_SYNC && this.paced ? 1 : 0) | DRAWABLES << 8);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		// mcopt.rec hook: the opt-in recorder (-Dmcopt.rec, mcopt.metal.rec) takes the finished frame, GUI included. Rec.ON is a constant false without it.
		if (mcopt.metal.rec.Rec.ON) mcopt.metal.rec.Rec.frame(this.encoder, textureView);
		long t0 = WaitStats.ON ? System.nanoTime() : 0;
		if (this.paced && !Native.pace(PACE_MARGIN_S)) {
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - t0, 0);
			return;
		}
		long t1 = WaitStats.ON ? System.nanoTime() : 0;
		if (WaitStats.ON && this.paced) WaitStats.wait(WaitStats.PACE, t1 - t0, 0);
		if (MetalEncoder.PRESENT_ACQUIRE) {
			this.encoder.presentAcquire(this.layer, textureView); // no nextDrawable here: the present side acquires it
			return;
		}
		MetalEvents.Operation event = this.encoder.drawableEvent();
		if (event != null) {
			event.surfaceLayer = this.layer; event.surfaceGeneration = this.generation;
			event.surfaceWidth = this.width; event.surfaceHeight = this.height;
		}
		long drawable;
		try {
			drawable = this.vsync && this.drawableAcquire != 0
				? MetalEvents.nextDrawableBounded(this.drawableAcquire, this.drawableWaitNanos, event)
				: MetalEvents.nextDrawable(this.layer, event);
		} finally {
			MetalEvents.end(event);
		}
		if (WaitStats.ON) WaitStats.wait(WaitStats.DRAWABLE, System.nanoTime() - t1, 0);
		if (drawable == 0) return; // no drawable (window hidden): skip the frame's present
		MetalEvents.Drawable lifetime = null;
		boolean scheduled = false;
		try {
			lifetime = this.encoder.drawableLifetime(drawable, this.layer, this.generation, this.width, this.height, this.vsync);
			scheduled = this.encoder.presentTexture(drawable, textureView);
		} finally {
			// Presentation owns what it needs now (queued mode retains until submission). Java retirement can be
			// several submits later; release our ownership here, including when presentTexture throws.
			boolean released = false;
			try { Native.release(drawable); released = true; }
			finally { MetalEvents.drawableEnd(lifetime, scheduled, released); }
		}
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens when it's committed.
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		if (this.drawableAcquire != 0) {
			Native.drawableAcquireClose(this.drawableAcquire);
			Native.release(this.drawableAcquire);
		}
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
