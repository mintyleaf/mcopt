// clang -O2 metal/src/metalChecks/native/lod-vertical.c -o /tmp/mcopt-lod-vertical && /tmp/mcopt-lod-vertical
#include <assert.h>
#include <stdio.h>
#include "../../main/native/lodmesh.c"

int main(void) {
	uint32_t geom[LM_TILE*LM_TILE]={0},crowns[LM_TILE*LM_TILE]={0},runs[LM_TILE*LM_TILE]={0};
	LmIn in={.geom=geom,.crown=crowns,.runs=runs,.logN=6,.block=8};
	geom[0]=(140+512)|GEOM_VALID|GEOM_CROWN|(60u<<15)|(8u<<25);
	crowns[0]=(64+512)|CROWN_SEGMENTED|CROWN_BOUNDED;
	runs[0]=1023u|(1u<<16)|(1u<<22)|(3u<<28);
	Col c;column(&in,0,0,&c);
	assert(c.n==5);
	for(int y=0;y<160;y++) {
		int occupied=0;
		for(int k=0;k<c.n;k++) occupied|=y>=c.lo[k] && y<c.hi[k];
		int expected=(y>=56&&y<64)||(y>=80&&y<100)||(y>=112&&y<114)||(y>=124&&y<126)||(y>=136&&y<140);
		assert(occupied==expected);
	}
	geom[0]=(200+512)|GEOM_VALID|GEOM_CROWN|(40u<<15)|(1u<<21)|(8u<<25);
	crowns[0]=(32+512)|CROWN_SEGMENTED|CROWN_BOUNDED;runs[0]=7u|(1u<<10)|(1u<<20)|(1u<<26);
	column(&in,0,0,&c);assert(c.n==5);
	for(int y=0;y<220;y++) {
		int occupied=0;for(int k=0;k<c.n;k++) occupied|=y>=c.lo[k]&&y<c.hi[k];
		int expected=(y>=24&&y<32)||(y>=40&&y<58)||(y>=100&&y<106)||(y>=160&&y<166)||(y>=196&&y<200);
		assert(occupied==expected);
	}
	// Rooted plants touch the ground. Keep their own material, without emitting buried interface faces.
	geom[0]=(72+512)|GEOM_VALID|GEOM_CROWN|(8u<<15);
	crowns[0]=(64+512);runs[0]=0;column(&in,0,0,&c);
	assert(c.n==2 && c.kind[0]==LM_K_UNDER && c.kind[1]==LM_K_CROWN);
	uint32_t *header=calloc(64*LM_HDR,sizeof(uint32_t)),*quads=calloc(1<<18,sizeof(uint32_t));
	int count=lm_mesh(&in,header,quads,1<<17),top=0;
	assert(count>0);
	for(int i=0;i<count;i++) {
		uint32_t q=quads[2*i],h=quads[2*i+1];int face=q>>26&7,y=(h&4095)-512;
		assert(!((face==LM_F_TOP||face==LM_F_BOTTOM) && y==64));
		if(face==LM_F_TOP && y==72) {assert((q>>29&3)==LM_K_CROWN);top++;}
	}
	assert(top==1);free(header);free(quads);
	puts("PASS: native mesh keeps four vertical air gaps and finite lower terrain; rooted crowns retain their material with no buried interface faces");
}
