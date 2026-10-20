#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void require(int condition,const char* message) {if(!condition){fprintf(stderr,"FAIL: %s\n",message);exit(1);}}
static id<MTLComputePipelineState> pipeline(id<MTLDevice> device,id<MTLLibrary> library,NSString* name,int pass) {
    NSError* error=nil;id<MTLFunction> f;
    if(pass>=0){MTLFunctionConstantValues* values=[MTLFunctionConstantValues new];bool flag=pass!=0;[values setConstantValue:&flag type:MTLDataTypeBool atIndex:0];f=[library newFunctionWithName:name constantValues:values error:&error];[values release];}
    else f=[library newFunctionWithName:name];
    if(!f){fprintf(stderr,"%s\n",error.description.UTF8String);exit(1);}
    id<MTLComputePipelineState> p=[device newComputePipelineStateWithFunction:f error:&error];[f release];
    if(!p){fprintf(stderr,"%s\n",error.description.UTF8String);exit(1);}return p;
}
int main(int argc,const char** argv) { @autoreleasepool {
    require(argc==4,"Pass production shader, reference and test kernels");
    NSMutableString* source=[NSMutableString stringWithString:@"#define SEAM_HANDOFF 1\n"];
    for(int i=1;i<4;i++){NSString* text=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[i]] encoding:NSUTF8StringEncoding error:nil];require(text!=nil,"Cannot read shader");[source appendString:text];[source appendString:@"\n"];}
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();require(device!=nil,"Metal device unavailable");NSError* error=nil;
    NSString* production=[NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil];
    id<MTLLibrary> plain=[device newLibraryWithSource:production options:nil error:&error];
    if(!plain){fprintf(stderr,"%s\n",error.description.UTF8String);return 1;}
    id<MTLComputePipelineState> plainQuads=pipeline(device,plain,@"lod_mesh_quads",-1);[plainQuads release];[plain release];
    id<MTLLibrary> library=[device newLibraryWithSource:source options:nil error:&error];
    if(!library){fprintf(stderr,"%s\n",error.description.UTF8String);return 1;}
    id<MTLComputePipelineState> setup=pipeline(device,library,@"test_setup",-1),append=pipeline(device,library,@"test_append",-1),
        finish=pipeline(device,library,@"lod_mesh_finish2",-1),opaque=pipeline(device,library,@"test_validate",0),handoff=pipeline(device,library,@"test_validate",1);
    // Also compile actual production vertex specializations; the kernel checks compare their shared decoder.
    for(int pass=0;pass<2;pass++){MTLFunctionConstantValues* v=[MTLFunctionConstantValues new];bool p=pass;[v setConstantValue:&p type:MTLDataTypeBool atIndex:0];id<MTLFunction> f=[library newFunctionWithName:@"lod_mesh_vs2" constantValues:v error:&error];require(f!=nil,"Production vertex specialization failed");[f release];[v release];}
    id<MTLCommandQueue> queue=[device newCommandQueue];id<MTLBuffer> b[31]={nil};
    NSUInteger sizes[31]={0};sizes[0]=4096;sizes[1]=5*16*16;sizes[4]=512;sizes[7]=300*16;sizes[12]=sizes[13]=5*256*256*4;
    sizes[17]=257*16;sizes[18]=512;sizes[19]=16;sizes[22]=4096;sizes[23]=257*32;sizes[24]=(512+257*2)*4;
    for(int i=0;i<31;i++)if(sizes[i])b[i]=[device newBufferWithLength:sizes[i] options:MTLResourceStorageModeShared];
    const uint32_t caps[]={1,17,32,63,64,65,257,300};
    for(int test=0;test<8;test++) {
        uint32_t params[2]={257,caps[test]};id<MTLCommandBuffer> cmd=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[cmd computeCommandEncoder];
        for(int i=0;i<31;i++)if(b[i])[c setBuffer:b[i] offset:0 atIndex:i];[c setBytes:params length:sizeof(params) atIndex:9];
        [c setComputePipelineState:setup];[c dispatchThreads:MTLSizeMake(5*256*256,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
        [c setComputePipelineState:append];[c dispatchThreads:MTLSizeMake(320,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
        [c setComputePipelineState:finish];[c dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
        for(int pass=0;pass<2;pass++){[c setComputePipelineState:pass?handoff:opaque];[c dispatchThreads:MTLSizeMake(((caps[test]+63)/64)*64*4,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];}
        [c endEncoding];[cmd commit];[cmd waitUntilCompleted];
        if(cmd.status!=MTLCommandBufferStatusCompleted){fprintf(stderr,"%s\n",cmd.error.description.UTF8String);return 1;}
        uint32_t* args=b[4].contents;uint32_t o=args[28],h=args[29],n=caps[test]<257?caps[test]:257;
        require(args[14]==n && o+h==n,"Partition count/cap mismatch");require(args[1]==(o+63)/64 && args[113]==(h+63)/64,"Indirect draw counts mismatch");
        require(*(uint32_t*)b[19].contents==0,"New vertex output differs from reference (or tail emitted geometry)");
        bool seen[257]={false};uint32_t* records=b[7].contents;uint32_t* input=b[17].contents;
        for(uint32_t i=0;i<n;i++){
            uint32_t index=i<o?i:caps[test]-1-(i-o),id=257;
            for(uint32_t k=0;k<257;k++)if(!memcmp(records+4*index,input+4*k,16)){id=k;break;}
            require(id<257 && !seen[id],"Lost/duplicate survivor or partition overlap");seen[id]=true;
        }
        printf("PASS cap %u: %u opaque + %u handoff; all corners/info match prior vertex path, tails hidden, records unique; draw batches %u vs prior %u\n",caps[test],o,h,args[1]+args[113],2*((n+63)/64));
    }
    // The depth-facet path uses the opaque vertex specialization with an unpartitioned list.
    uint32_t* facetArgs=b[4].contents;facetArgs[31]=0;facetArgs[14]=257;
    memcpy(b[7].contents,b[17].contents,257*16);*(uint32_t*)b[19].contents=0;
    uint32_t facetParams[2]={257,300};
    id<MTLCommandBuffer> cmd=[queue commandBuffer];id<MTLComputeCommandEncoder> c=[cmd computeCommandEncoder];
    for(int i=0;i<31;i++)if(b[i])[c setBuffer:b[i] offset:0 atIndex:i];[c setBytes:facetParams length:sizeof(facetParams) atIndex:9];
    for(int pass=0;pass<2;pass++){[c setComputePipelineState:pass?handoff:opaque];[c dispatchThreads:MTLSizeMake(320*4,1,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];}
    [c endEncoding];[cmd commit];[cmd waitUntilCompleted];
    require(cmd.status==MTLCommandBufferStatusCompleted && *(uint32_t*)b[19].contents==0,"Depth-facet fallback differs from reference");
    puts("PASS: no-handoff production shader compiles; unpartitioned depth facets preserve prior vertex behavior");
    for(int i=0;i<31;i++)[b[i] release];[setup release];[append release];[finish release];[opaque release];[handoff release];[library release];[queue release];[device release];
} return 0; }
