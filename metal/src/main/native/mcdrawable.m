// A single outstanding CAMetalLayer acquisition per surface. The render thread waits at most one refresh plus slack;
// the worker owns the layer until acquisition ends, including if SDL destroys the view in the meantime.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/CABase.h>
#include <os/lock.h>
#include <time.h>

@interface McDrawableAcquire : NSObject {
@public
	CAMetalLayer *layer;
	os_unfair_lock lock;
	dispatch_semaphore_t wake;
	BOOL pending, ready, closed;
	int status;
	uint64_t generation;
	int width, height;
	id<CAMetalDrawable> drawable;
	double workerStart, workerEnd, workerCpuMs;
}
@end

@implementation McDrawableAcquire
- (void)dealloc {
	[drawable release];
	[layer release];
	dispatch_release(wake);
	[super dealloc];
}
@end

static double threadCpuMs(void) {
	struct timespec t;
	return clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t) == 0 ? t.tv_sec * 1000.0 + t.tv_nsec / 1e6 : -1;
}

McDrawableAcquire *mc_drawable_acquire_new(CAMetalLayer *layer) {
	McDrawableAcquire *s = [McDrawableAcquire new];
	s->layer = [layer retain];
	s->lock = OS_UNFAIR_LOCK_INIT;
	s->wake = dispatch_semaphore_create(0);
	s->width = (int)layer.drawableSize.width; s->height = (int)layer.drawableSize.height;
	return s;
}

// Invalidates cached and in-progress results before configuring a new size/mode. No waiting for a worker.
void mc_drawable_acquire_invalidate(McDrawableAcquire *s, int width, int height) {
	os_unfair_lock_lock(&s->lock);
	s->generation++; s->width = width; s->height = height;
	id<CAMetalDrawable> stale = s->drawable; s->drawable = nil; s->ready = NO;
	os_unfair_lock_unlock(&s->lock);
	[stale release];
}

void mc_drawable_acquire_close(McDrawableAcquire *s) {
	if (!s) return;
	os_unfair_lock_lock(&s->lock);
	s->closed = YES; s->generation++;
	id<CAMetalDrawable> stale = s->drawable; s->drawable = nil; s->ready = NO;
	os_unfair_lock_unlock(&s->lock);
	[stale release];
	dispatch_semaphore_signal(s->wake);
}

static void acquireWork(McDrawableAcquire *s, uint64_t generation, int width, int height) {
	@autoreleasepool {
		double cpu = threadCpuMs(), start = CACurrentMediaTime();
		id<CAMetalDrawable> acquired = [[s->layer nextDrawable] retain];
		double end = CACurrentMediaTime(), cpuEnd = threadCpuMs();
		// A resize may finish while the driver is acquiring. Never hand an old-size drawable to the new surface.
		BOOL sizeMatches = !acquired || (acquired.texture.width == width && acquired.texture.height == height);
		os_unfair_lock_lock(&s->lock);
		if (!s->closed) {
			BOOL valid = s->generation == generation && sizeMatches;
			if (valid) { s->drawable = acquired; acquired = nil; }
			s->status = !valid ? 4 : s->drawable ? 1 : 0;
			s->ready = YES; s->workerStart = start; s->workerEnd = end;
			s->workerCpuMs = cpu >= 0 && cpuEnd >= cpu ? cpuEnd - cpu : -1;
		}
		s->pending = NO;
		os_unfair_lock_unlock(&s->lock);
		[acquired release];
		dispatch_semaphore_signal(s->wake);
	}
}

// out: caller host start/end/thread CPU ms, worker host start/end/thread CPU ms, result status.
// Status 0: driver returned nil; 1: drawable; 2: bounded wait expired; 3: closed; 4: stale size/generation.
static id<CAMetalDrawable> acquireNext(McDrawableAcquire *s, int64_t waitNs, double *out) {
	dispatch_time_t deadline = dispatch_time(DISPATCH_TIME_NOW, MAX(0, waitNs));
	for (;;) {
		os_unfair_lock_lock(&s->lock);
		if (s->closed || s->ready) {
			BOOL closed = s->closed;
			id<CAMetalDrawable> result = closed ? nil : s->drawable;
			if (!closed) { s->drawable = nil; s->ready = NO; }
			if (out) {
				out[3] = closed ? 0 : s->workerStart; out[4] = closed ? 0 : s->workerEnd;
				out[5] = closed ? -1 : s->workerCpuMs; out[6] = closed ? 3 : s->status;
			}
			os_unfair_lock_unlock(&s->lock);
			return result; // transfer our retained ownership to the caller
		}
		BOOL launch = !s->pending;
		uint64_t generation = s->generation;
		int width = s->width, height = s->height;
		if (launch) s->pending = YES;
		os_unfair_lock_unlock(&s->lock);
		if (launch) {
			// Capture a C pointer, with explicit ownership until the worker exits, rather than the Java surface's lifetime.
			void *retained = (void *)[s retain];
			dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^{
				@autoreleasepool {
					McDrawableAcquire *request = (McDrawableAcquire *)retained;
					acquireWork(request, generation, width, height);
					[request release];
				}
			});
		}
		if (dispatch_semaphore_wait(s->wake, deadline) != 0) {
			// A result delivered at the deadline remains cached for the next frame. There is still only one worker.
			if (out) { out[3] = out[4] = 0; out[5] = -1; out[6] = 2; }
			return nil;
		}
		// Signals may be left over from a result consumed before its worker signalled; recheck with the same deadline.
	}
}

id<CAMetalDrawable> mc_drawable_acquire_next(McDrawableAcquire *s, int64_t waitNs, double *out) {
	[s retain]; // also safe if close runs concurrently with a bounded waiter
	double cpu = out ? threadCpuMs() : 0, start = out ? CACurrentMediaTime() : 0;
	id<CAMetalDrawable> result = acquireNext(s, waitNs, out);
	if (out) {
		out[0] = start; out[1] = CACurrentMediaTime();
		double cpuEnd = threadCpuMs(); out[2] = cpu >= 0 && cpuEnd >= cpu ? cpuEnd - cpu : -1;
	}
	[s release];
	return result;
}

// Recovery-only readiness wait: never launches a worker, consumes a drawable or holds the state lock while waiting.
// 1 means the previously outstanding acquisition is still unavailable after the bounded wait; 0 resumes normal drawing.
int mc_drawable_acquire_await(McDrawableAcquire *s, int64_t waitNs) {
	[s retain];
	dispatch_time_t deadline = dispatch_time(DISPATCH_TIME_NOW, MAX(0, waitNs));
	int pending;
	for (;;) {
		os_unfair_lock_lock(&s->lock);
		pending = !s->closed && s->pending && !s->ready;
		os_unfair_lock_unlock(&s->lock);
		if (!pending) break;
		if (dispatch_semaphore_wait(s->wake, deadline) != 0) {
			os_unfair_lock_lock(&s->lock);
			pending = !s->closed && s->pending && !s->ready;
			os_unfair_lock_unlock(&s->lock);
			break;
		}
	}
	[s release];
	return pending;
}
