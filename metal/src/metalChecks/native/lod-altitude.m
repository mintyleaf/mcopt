#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

int main(int argc,const char** argv) { @autoreleasepool {
    if(argc!=3)return 2;
    NSMutableString* source=[NSMutableString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil];
    [source appendString:[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:nil]];
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();NSError* error=nil;
    id<MTLLibrary> library=[device newLibraryWithSource:source options:nil error:&error];
    if(!library){fprintf(stderr,"%s\n",error.description.UTF8String);return 1;}
    id<MTLFunction> fn=[library newFunctionWithName:@"test_altitude_levels"];
    id<MTLComputePipelineState> pipeline=[device newComputePipelineStateWithFunction:fn error:&error];
    if(!pipeline){fprintf(stderr,"%s\n",error.description.UTF8String);return 1;}
    const int cases=97,bytes=432;
    id<MTLBuffer> frames=[device newBufferWithLength:cases*bytes options:MTLResourceStorageModeShared];
    id<MTLBuffer> errors=[device newBufferWithLength:16 options:MTLResourceStorageModeShared];memset(errors.contents,0,16);
    for(int k=0;k<cases;k++) {
        uint8_t* f=(uint8_t*)frames.contents+k*bytes;memset(f,0,bytes);
        float scale=.25f+(float)k/128.f;
        int32_t origin[4]={-8197,320,4099,5},counts[4]={32,16,0,0};
        float cam[4]={.25f,.125f,.75f,16384},selection[4]={0,13.0f,-11.0f,0};
        memcpy(f+64,cam,16);memcpy(f+80,origin,16);memcpy(f+112,selection,16);memcpy(f+128,counts,16);
        for(int L=0;L<5;L++){float sw=956.f*(1<<L)*scale;memcpy(f+336+4*L,&sw,4);}
    }
    id<MTLCommandQueue> queue=[device newCommandQueue];id<MTLCommandBuffer> command=[queue commandBuffer];id<MTLComputeCommandEncoder> encoder=[command computeCommandEncoder];
    [encoder setComputePipelineState:pipeline];[encoder setBuffer:frames offset:0 atIndex:0];[encoder setBuffer:errors offset:0 atIndex:1];
    [encoder dispatchThreads:MTLSizeMake(cases*4096,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];[encoder endEncoding];[command commit];[command waitUntilCompleted];
    if(command.status!=MTLCommandBufferStatusCompleted){fprintf(stderr,"%s\n",command.error.description.UTF8String);return 1;}
    uint32_t failed=*(uint32_t*)errors.contents;
    if(failed){fprintf(stderr,"FAIL: %u tier gaps/overlaps or near-ring changes\n",failed);return 1;}
    puts("PASS: actual Metal tier selector, 97 detail scales, 794624 point/selection-camera checks, unique ownership and protected near ring");
    [frames release];[errors release];[queue release];[pipeline release];[fn release];[library release];[device release];return 0;
}}
