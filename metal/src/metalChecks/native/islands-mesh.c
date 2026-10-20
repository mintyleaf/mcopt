// clang -O2 metal/src/metalChecks/native/islands-mesh.c metal/src/main/native/lodmesh.c -o /tmp/mcopt-islands-mesh
#include "../../main/native/lodmesh.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

int main(void) {
	uint32_t *g=calloc(4096,4),*cr=calloc(4096,4),*runs=calloc(4096,4);
	uint32_t *header=calloc(64*LM_HDR,4),*quads=calloc(65536*2,4);
	for(int i=0;i<4096;i++) g[i]=0x2000; // known empty space, not an unknown tile
	LmIn in={.geom=g,.crown=cr,.runs=runs,.logN=6,.block=8,.neighbors=15};
	assert(lm_mesh(&in,header,quads,65536)==0);
	for(int roof=0;roof<2;roof++) {
		for(int z=16;z<32;z++) for(int x=16;x<32;x++) {
			int i=z*64+x;
			g[i]=(roof?210:80)+512|0x2000u|(roof?0x4000u|(63u<<15):0);
			cr[i]=(roof?80:40)+512|(1u<<29);
			runs[i]=(40+512)|((120+512)<<12);
		}
		int n=lm_mesh(&in,header,quads,65536),top=0,bottom=0;
		assert(n>0);
		uint32_t *part=header+(2*8+2)*LM_HDR;
		int bottomCode=((part[16]>>24)&15)|(((part[24]>>24)&15)<<4);
		assert(bottomCode*2-66==40); // all visibility paths need the actual lower interval, not an infinite column
		for(int i=0;i<n;i++) {
			uint32_t q=quads[2*i],v=quads[2*i+1];
			int face=(q>>26)&7,kind=(q>>29)&3,lo=(v&4095)-512,hi=((v>>12)&4095)-512;
			assert(!(q&(1u<<31))); // no dynamic skirt may pull an island's walls down to the world floor
			if(roof && kind==LM_K_CROWN) { assert(lo>=120 && lo<=210 && hi>=120 && hi<=210); }
			else { assert(lo>=40 && lo<=80 && hi>=40 && hi<=80); }
			int area=(((q>>14)&63)+1)*(((q>>20)&63)+1);
			if(face==LM_F_TOP) top+=area;
			if(face==LM_F_BOTTOM) bottom+=area;
		}
		assert(top==256*(1+roof) && bottom==256*(1+roof));
	}
	free(g);free(cr);free(runs);free(header);free(quads);
	puts("PASS: known void emits no faces; islands and roofs retain finite undersides, including spans over 63 blocks; no ground-reaching skirts");
}
