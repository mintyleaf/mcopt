#import <Foundation/Foundation.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/CATransaction.h>
#import "mcmetal.h"
#include <stdatomic.h>
#include <unistd.h>

extern Ctx *mc_create(char *,int,char *,int);
extern Enc *mc_enc_new(Ctx *);
extern void mc_layer_configure(Ctx *,CAMetalLayer *,int,int,int);
extern id mc_drawable_acquire_new(CAMetalLayer *);
extern void mc_drawable_acquire_invalidate(id,int,int);
extern void mc_drawable_acquire_close(id);
extern id<CAMetalDrawable> mc_drawable_acquire_next(id,int64_t,double *);
extern id<CAMetalDrawable> mc_layer_next(CAMetalLayer *);
extern uint64_t mc_drawable_id(id<MTLDrawable>);
extern void mc_present(Enc *,id<CAMetalDrawable>,id<MTLTexture>);
extern id<MTLCommandBuffer> mc_enc_commit(Enc *);
static void require(BOOL ok,const char *message) { if(!ok){fprintf(stderr,"%s\n",message);exit(1);} }
static _Atomic int destroyed;

// Hold the actual driver call behind a deterministic gate, then vend real Metal drawables.
@interface DelayedLayer : CAMetalLayer {
@public dispatch_semaphore_t entered, gate; _Atomic int calls; BOOL returnNil;
}
@end
@implementation DelayedLayer
- (instancetype)init {
	if((self=[super init])) { entered=dispatch_semaphore_create(0);gate=dispatch_semaphore_create(0); }
	return self;
}
- (id<CAMetalDrawable>)nextDrawable {
	atomic_fetch_add(&calls,1);dispatch_semaphore_signal(entered);
	dispatch_semaphore_wait(gate,DISPATCH_TIME_FOREVER);
	return returnNil?nil:[super nextDrawable];
}
- (void)dealloc { atomic_fetch_add(&destroyed,1);dispatch_release(entered);dispatch_release(gate);[super dealloc]; }
@end

static void permit(DelayedLayer *layer) { dispatch_semaphore_signal(layer->gate); }
static void entered(DelayedLayer *layer) {
	require(dispatch_semaphore_wait(layer->entered,dispatch_time(DISPATCH_TIME_NOW,2*NSEC_PER_SEC))==0,"Worker did not enter acquisition");
}
static id<CAMetalDrawable> result(id state) {
	double out[7];
	for(int i=0;i<100;i++) {
		id<CAMetalDrawable> d=mc_drawable_acquire_next(state,20*NSEC_PER_MSEC,out);
		if(d) return d;
		require(out[6]==2 || out[6]==4,"Unexpected nil/closed acquisition");
	}
	require(NO,"No completed drawable");return nil;
}
static uint64_t image(Ctx *ctx,Enc *enc,id<CAMetalDrawable> drawable,id<MTLTexture> source) {
	mc_present(enc,drawable,source);
	id<MTLCommandBuffer> rendered=mc_enc_commit(enc);[rendered waitUntilCompleted];
	require(rendered.status==MTLCommandBufferStatusCompleted,"Production present failed");[rendered release];
	NSUInteger w=drawable.texture.width,h=drawable.texture.height;
	id<MTLBuffer> pixels=[ctx->device newBufferWithLength:w*h*4 options:MTLResourceStorageModeShared];
	id<MTLCommandBuffer> cmd=[ctx->queue commandBuffer];id<MTLBlitCommandEncoder> b=[cmd blitCommandEncoder];
	[b copyFromTexture:drawable.texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(w,h,1)
		toBuffer:pixels destinationOffset:0 destinationBytesPerRow:w*4 destinationBytesPerImage:w*h*4];
	[b endEncoding];[cmd commit];[cmd waitUntilCompleted];
	require(cmd.status==MTLCommandBufferStatusCompleted,"Drawable readback failed");
	uint64_t hash=1469598103934665603ULL;unsigned char *bytes=pixels.contents;
	for(NSUInteger i=0;i<w*h*4;i++) hash=(hash^bytes[i])*1099511628211ULL;
	[pixels release];return hash;
}

int main(void) { @autoreleasepool {
	char name[256],error[4096]={0};Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,error);
	DelayedLayer *layer=[DelayedLayer new];mc_layer_configure(ctx,layer,64,64,1);layer.framebufferOnly=NO;
	id state=mc_drawable_acquire_new(layer);double out[7];
	require(mc_drawable_acquire_next(state,NSEC_PER_MSEC,out)==nil && out[6]==2,"Blocked driver did not return a bounded timeout");
	entered(layer);
	for(int i=0;i<12;i++) require(mc_drawable_acquire_next(state,NSEC_PER_MSEC,out)==nil && out[6]==2,"Blocked acquire lost timeout status");
	for(int i=0;i<14;i++) require(mc_drawable_acquire_next(state,22*NSEC_PER_MSEC,out)==nil && out[6]==2,"Long blocked acquire lost bounded status");
	require(atomic_load(&layer->calls)==1,"Repeated render requests launched multiple blocked acquisitions");
	permit(layer);id<CAMetalDrawable> drawable=result(state);
	require(mc_drawable_id(drawable)==drawable.drawableID,"Diagnostic drawable identity changed");
	require(drawable.texture.width==64,"Completed drawable lost its size");
	Enc *enc=mc_enc_new(ctx);
	MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:64 height:64 mipmapped:NO];
	td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;
	id<MTLTexture> source=[ctx->device newTextureWithDescriptor:td];uint32_t bytes[4096];
	for(int y=0;y<64;y++)for(int x=0;x<64;x++)bytes[y*64+x]=0xff000000|((x*4)<<16)|((y*4)<<8)|0x35;
	[source replaceRegion:MTLRegionMake2D(0,0,64,64) mipmapLevel:0 withBytes:bytes bytesPerRow:256];
	uint64_t bounded=image(ctx,enc,drawable,source);[drawable release];
	permit(layer);drawable=mc_layer_next(layer);uint64_t direct=image(ctx,enc,drawable,source);[drawable release];
	require(bounded==direct,"Worker acquisition changed rendered pixels");

	// Invalidate an acquisition started at64px, finish it after resize, then require a fresh128px result.
	require(mc_drawable_acquire_next(state,NSEC_PER_MSEC,out)==nil,"Unexpected eager drawable");
	mc_drawable_acquire_invalidate(state,128,128);mc_layer_configure(ctx,layer,128,128,1);layer.framebufferOnly=NO;
	permit(layer); // old generation must be discarded
	permit(layer); // next generation may finish
	drawable=result(state);require(drawable.texture.width==128,"Resize handed off an old drawable");
	bounded=image(ctx,enc,drawable,source);[drawable release];
	permit(layer);drawable=mc_layer_next(layer);direct=image(ctx,enc,drawable,source);[drawable release];
	require(bounded==direct,"Worker acquisition changed scaled/MetalFX pixels");
	for(int i=0;i<20;i++){
		permit(layer);drawable=result(state);
		for(int j=0;j<1000;j++) require(mc_drawable_id(drawable)==drawable.drawableID,"Repeated identity queries changed a live drawable");
		image(ctx,enc,drawable,source);[drawable release];
	}
	mc_drawable_acquire_close(state);[state release];[layer release];
	printf("PASS: one outstanding worker, bounded waits, real direct/worker pixels and MetalFX scale match; resize rejects stale results\n");

	// SDL-like owner destruction must not free a layer still used by an outstanding acquisition.
	int before=atomic_load(&destroyed);layer=[DelayedLayer new];mc_layer_configure(ctx,layer,64,64,1);
	state=mc_drawable_acquire_new(layer);
	require(mc_drawable_acquire_next(state,NSEC_PER_MSEC,out)==nil,"Unexpected unblocked worker");entered(layer);
	dispatch_semaphore_t finish=layer->gate;dispatch_retain(finish);
	mc_drawable_acquire_close(state);[state release];[layer release];
	require(atomic_load(&destroyed)==before,"Close freed a layer still used by its worker");
	dispatch_semaphore_signal(finish);dispatch_release(finish);
	[CATransaction flush]; // headless check has no AppKit run loop to commit layer changes
	for(int i=0;i<1000 && atomic_load(&destroyed)==before;i++)usleep(1000);
	require(atomic_load(&destroyed)==before+1,"Closed acquisition retained its surface after completing");

	layer=[DelayedLayer new];mc_layer_configure(ctx,layer,64,64,1);layer->returnNil=YES;
	state=mc_drawable_acquire_new(layer);permit(layer);
	require(mc_drawable_acquire_next(state,NSEC_PER_SEC,out)==nil && out[6]==0,"Hidden/nil drawable not preserved");
	mc_drawable_acquire_close(state);require(mc_drawable_acquire_next(state,0,out)==nil && out[6]==3,"Closed status not preserved");
	[state release];[layer release];[source release];free(enc);
	printf("PASS: close never waits for blocked driver, layer stays alive through completion, nil and closed results remain explicit\n");
}}
