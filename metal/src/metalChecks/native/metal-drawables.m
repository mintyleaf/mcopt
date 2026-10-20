// clang -fno-objc-arc -framework Foundation -framework Metal -framework QuartzCore -I metal/src/main/native \
// metal/src/metalChecks/native/metal-drawables.m metal/build/natives/natives/libmcmetal.dylib \
// -Wl,-rpath,"$PWD/metal/build/natives/natives" -o /tmp/mcopt-metal-drawables
#import <Foundation/Foundation.h>
#import <QuartzCore/CAMetalLayer.h>
#import "mcmetal.h"
#include <stdio.h>
#include <stdlib.h>

extern Ctx *mc_create(char *, int, char *, int);
extern Enc *mc_enc_new(Ctx *);
extern void mc_layer_configure(Ctx *, CAMetalLayer *, int, int, int);
extern id<CAMetalDrawable> mc_layer_next(CAMetalLayer *);
extern void mc_present(Enc *, id<CAMetalDrawable>, id<MTLTexture>);
extern int mc_present_queued(Enc *, id<CAMetalDrawable>, id<MTLTexture>);
extern id<MTLCommandBuffer> mc_enc_commit(Enc *);
extern void mc_cmd_timing(id<MTLCommandBuffer>, double *);

static void require(int ok, const char *message) { if (!ok) { fprintf(stderr,"%s\n", message); exit(1); } }

int main(void) { @autoreleasepool {
    char name[256]={0}, error[4096]={0};
    Ctx *ctx=mc_create(name,sizeof name,error,sizeof error);require(ctx!=NULL,error);
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:64 height:64 mipmapped:NO];
    td.storageMode=MTLStorageModeShared;td.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> source=[ctx->device newTextureWithDescriptor:td];
    uint32_t pixels[4096];for(int i=0;i<4096;i++)pixels[i]=0xffc08020;
    [source replaceRegion:MTLRegionMake2D(0,0,64,64) mipmapLevel:0 withBytes:pixels bytesPerRow:256];
    for(int mode=0;mode<3;mode++) { @autoreleasepool {
        CAMetalLayer *layer=[CAMetalLayer new];mc_layer_configure(ctx,layer,64,64,1);layer.allowsNextDrawableTimeout=YES;
        Enc *enc=mc_enc_new(ctx);
        id<MTLCommandBuffer> commands[6]={0};id<CAMetalDrawable> held[6]={0};
        int count=6;
        for(int frame=0;frame<count;frame++) { @autoreleasepool {
            id<CAMetalDrawable> drawable=mc_layer_next(layer);require(drawable!=nil,"Layer did not vend a drawable");
            if(mode==2) mc_present_queued(enc,drawable,source);else mc_present(enc,drawable,source);
            if(mode==0) held[frame]=drawable;else [drawable release];
            commands[frame]=mc_enc_commit(enc);[commands[frame] waitUntilCompleted];
            require(commands[frame].status==MTLCommandBufferStatusCompleted,"GPU rejected the present after early release");
            double times[6]={0};mc_cmd_timing(commands[frame],times);
            require(times[5]==MTLCommandBufferStatusCompleted && times[2]>0 && times[3]>=times[2] && times[4]>=times[3],"Invalid GPU/host timeline");
        }}
        if(mode==0) {
            // Control only: current drivers may recycle an already-presented drawable even while its object is retained.
            double before=CACurrentMediaTime();id<CAMetalDrawable> extra=mc_layer_next(layer);
            printf("CONTROL: old ownership, completed frame buffers still retained: next drawable %s, wait %.1f ms\n",extra?"available":"timed out",(CACurrentMediaTime()-before)*1e3);
            if(extra) [extra release];
            for(int i=0;i<count;i++) [held[i] release];
            id<CAMetalDrawable> recycled=mc_layer_next(layer);require(recycled!=nil,"Releasing owners did not restore pool availability");[recycled release];
        } else {
            printf("PASS: %s releases drawable ownership before commit; pool recycles while six completed frame command buffers remain retained\n",mode==1?"direct present":"queued present");
        }
        for(int i=0;i<count;i++) [commands[i] release];
        [layer release];free(enc);
    }}
    [source release];
}}
