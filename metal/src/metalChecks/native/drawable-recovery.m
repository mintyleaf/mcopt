#import "../../main/native/mcdrawable.m"
#include <assert.h>
#include <unistd.h>

@interface ProbeTexture : NSObject
@property(readonly) NSUInteger width, height;
@end
@implementation ProbeTexture
- (NSUInteger)width { return 32; }
- (NSUInteger)height { return 32; }
@end

@interface ProbeDrawable : NSObject {
	ProbeTexture *texture;
}
@property(readonly) ProbeTexture *texture;
@end
@implementation ProbeDrawable
- (id)init { if ((self=[super init])) texture=[ProbeTexture new]; return self; }
- (ProbeTexture *)texture { return texture; }
- (void)dealloc { [texture release]; [super dealloc]; }
@end

@interface ProbeLayer : NSObject {
@public
	dispatch_semaphore_t started, gate;
	int calls;
}
@property(readonly) CGSize drawableSize;
@end
@implementation ProbeLayer
- (id)init {
	if ((self=[super init])) { started=dispatch_semaphore_create(0); gate=dispatch_semaphore_create(0); }
	return self;
}
- (CGSize)drawableSize { return CGSizeMake(32,32); }
- (id)nextDrawable {
	__atomic_add_fetch(&calls,1,__ATOMIC_RELAXED);
	dispatch_semaphore_signal(started);
	dispatch_semaphore_wait(gate,DISPATCH_TIME_FOREVER);
	return [[[ProbeDrawable alloc] init] autorelease];
}
- (void)dealloc { dispatch_release(started);dispatch_release(gate);[super dealloc]; }
@end

static void started(ProbeLayer *layer) {
	assert(dispatch_semaphore_wait(layer->started,dispatch_time(DISPATCH_TIME_NOW,2*NSEC_PER_SEC))==0);
}

int main(void) {
 @autoreleasepool {
	ProbeLayer *layer=[ProbeLayer new];
	McDrawableAcquire *s=mc_drawable_acquire_new((CAMetalLayer *)layer);
	double out[7];
	assert(mc_drawable_acquire_await(s,0)==0); // idle: no acquisition launched
	assert(layer->calls==0);
	assert(mc_drawable_acquire_next(s,1000000,out)==nil && out[6]==2);started(layer);
	double before=CACurrentMediaTime();
	assert(mc_drawable_acquire_await(s,1000000)==1);
	assert(CACurrentMediaTime()-before<.5 && layer->calls==1);
	dispatch_semaphore_signal(layer->gate);
	assert(mc_drawable_acquire_await(s,NSEC_PER_SEC)==0);
	assert(mc_drawable_acquire_await(s,0)==0); // does not consume the ready result
	id drawable=mc_drawable_acquire_next(s,0,out);
	assert(drawable!=nil && out[6]==1 && layer->calls==1);[drawable release];

	// Resize while the worker is held: old-generation/old-size results cannot escape.
	assert(mc_drawable_acquire_next(s,1000000,out)==nil);started(layer);
	mc_drawable_acquire_invalidate(s,64,64);
	dispatch_semaphore_signal(layer->gate);
	assert(mc_drawable_acquire_await(s,NSEC_PER_SEC)==0);
	assert(mc_drawable_acquire_next(s,0,out)==nil && out[6]==4);

	// Close wakes readiness waiters without waiting for the driver, whose worker retains state.
	mc_drawable_acquire_invalidate(s,32,32);
	assert(mc_drawable_acquire_next(s,1000000,out)==nil);started(layer);
	mc_drawable_acquire_close(s);before=CACurrentMediaTime();
	assert(mc_drawable_acquire_await(s,NSEC_PER_SEC)==0 && CACurrentMediaTime()-before<.5);
	assert(mc_drawable_acquire_next(s,0,out)==nil && out[6]==3);
	dispatch_semaphore_signal(layer->gate);
	for(int i=0;i<2000;i++) {
		os_unfair_lock_lock(&s->lock);BOOL pending=s->pending;os_unfair_lock_unlock(&s->lock);
		if(!pending)break;
		usleep(1000);
	}
	os_unfair_lock_lock(&s->lock);BOOL pending=s->pending;os_unfair_lock_unlock(&s->lock);assert(!pending);
	[s release];[layer release];
	puts("PASS: bounded recovery waits launch no workers, preserve ready ownership, reject stale resize results and wake safely on close");
 }
 return 0;
}
