package mcopt.metal.lod;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/** Server-thread structure scouting. Cheap placement candidates first, real STRUCTURE_STARTS validation, then FULL footprints. */
final class LodPoiPlan {
	record Work(ChunkPos pos, boolean probe) { }
	private final ChunkGeneratorStructureState state;
	private final List<RandomSpreadStructurePlacement> placements=new ArrayList<>();
	private final Set<Long> checked=new HashSet<>(), footprint=new LinkedHashSet<>();
	private final Set<Long> queued=new HashSet<>();
	private PriorityQueue<Long> candidates;
	private int cx=Integer.MIN_VALUE,cz,radius,placement,rx,rz,minRx,minRz,maxRx,maxRz;
	private int centerRx,centerRz,ring,ringStep,maxRing;
	private double fx,fz;
	private boolean scanning;
	private int scanBudget=64;
	int validated, structures;

	LodPoiPlan(ServerLevel level,int radius) {
		this.state=level.getChunkSource().getGeneratorState();this.radius=radius;
		for(var holder:this.state.possibleStructureSets()) {
			var set=holder.value();
			boolean surface=set.structures().stream().anyMatch(entry -> entry.structure().unwrapKey().map(key -> {
				String path=key.identifier().getPath();
				return !path.contains("mineshaft") && !path.contains("stronghold") && !path.contains("ancient_city")
					&& !path.contains("trial_chambers") && !path.contains("buried_treasure");
			}).orElse(true));
			if(surface && set.placement() instanceof RandomSpreadStructurePlacement p && p.spacing()>=4 && !this.placements.contains(p)) this.placements.add(p);
		}
	}

	Work next(int x,int z,double forwardX,double forwardZ,int rd) {
		Work work = nextFootprint(x,z,forwardX,forwardZ,rd);
		return work == null ? nextProbe(x,z,forwardX,forwardZ,rd) : work;
	}

	private boolean prepare(int x,int z,double forwardX,double forwardZ) {
		if(this.radius==0 || this.placements.isEmpty()) return false;
		if(this.cx==Integer.MIN_VALUE || Math.abs(x-this.cx)>=16 || Math.abs(z-this.cz)>=16) this.reset(x,z,forwardX,forwardZ);
		this.scan();
		return true;
	}

	Work nextFootprint(int x,int z,double forwardX,double forwardZ,int rd) {
		return nextFootprint(x,z,forwardX,forwardZ,rd,key -> false);
	}
	Work nextFootprint(int x,int z,double forwardX,double forwardZ,int rd,java.util.function.LongPredicate busy) {
		if(!prepare(x,z,forwardX,forwardZ)) return null;
		for(var it=this.footprint.iterator();it.hasNext();) {
			long key=it.next();
			if(busy.test(key)) continue;
			it.remove();
			int px=(int)(key>>32),pz=(int)key;
			if(within(px,pz,x,z,this.radius+16) && (Math.abs(px-x)>rd+2 || Math.abs(pz-z)>rd+2)) return new Work(new ChunkPos(px,pz),false);
		}
		return null;
	}

	Work nextProbe(int x,int z,double forwardX,double forwardZ,int rd) {
		return nextProbe(x,z,forwardX,forwardZ,rd,key -> false);
	}
	Work nextProbe(int x,int z,double forwardX,double forwardZ,int rd,java.util.function.LongPredicate busy) {
		if(!prepare(x,z,forwardX,forwardZ)) return null;
		var deferred=new ArrayList<Long>();
		try {
		while(!this.candidates.isEmpty()) {
			long key=this.candidates.poll();this.queued.remove(key);
			if(busy.test(key)) {deferred.add(key);continue;}
			int px=(int)(key>>32),pz=(int)key;
			if(!this.checked.add(key) || !within(px,pz,x,z,this.radius) || Math.abs(px-x)<=rd+2 && Math.abs(pz-z)<=rd+2) continue;
			return new Work(new ChunkPos(px,pz),true);
		}
		return null;
		} finally {
			for(long key:deferred) if(this.queued.add(key)) this.candidates.add(key);
		}
	}

	private static boolean within(int x,int z,int cx,int cz,int radius) {
		long dx=(long)x-cx,dz=(long)z-cz;return dx*dx+dz*dz<=(long)radius*radius;
	}
	private void reset(int x,int z,double fx,double fz) {
		this.cx=x;this.cz=z;this.fx=fx;this.fz=fz;this.placement=0;this.scanning=true;
		this.checked.removeIf(key -> !within((int)(key>>32),(int)(long)key,x,z,this.radius+32));
		this.footprint.removeIf(key -> !within((int)(key>>32),(int)(long)key,x,z,this.radius+16));
		if(this.checked.size()>8192) this.checked.clear();
		this.queued.clear();
		this.candidates=new PriorityQueue<>(Comparator.comparingDouble(key -> LodRefinePlan.score((int)(key>>32)-x,(int)(long)key-z,fx,fz)));
		this.regionBounds();
	}
	private void regionBounds() {
		if(this.placement>=this.placements.size()) {this.scanning=false;return;}
		int spacing=this.placements.get(this.placement).spacing();
		this.minRx=Math.floorDiv(this.cx-this.radius,spacing);this.minRz=Math.floorDiv(this.cz-this.radius,spacing);
		this.maxRx=Math.floorDiv(this.cx+this.radius,spacing);this.maxRz=Math.floorDiv(this.cz+this.radius,spacing);
		this.rx=this.centerRx=Math.floorDiv(this.cx,spacing);this.rz=this.centerRz=Math.floorDiv(this.cz,spacing);
		this.ring=this.ringStep=0;this.maxRing=Math.max(Math.max(this.centerRx-this.minRx,this.maxRx-this.centerRx),Math.max(this.centerRz-this.minRz,this.maxRz-this.centerRz));
	}
	private void advanceRegion() {
		if(this.ring==0 || ++this.ringStep>=8*this.ring) {this.ring++;this.ringStep=0;}
		if(this.ring>this.maxRing) {this.placement++;this.regionBounds();return;}
		int side=this.ringStep/(2*this.ring),step=this.ringStep%(2*this.ring),r=this.ring;
		this.rx=this.centerRx+switch(side) {case 0 -> -r+step;case 1 -> r;case 2 -> r-step;default -> -r;};
		this.rz=this.centerRz+switch(side) {case 0 -> -r;case 1 -> -r+step;case 2 -> r;default -> r-step;};
	}
	void beginTick() {this.scanBudget=64;}
	private void scan() {
		while(this.scanning && this.scanBudget>0 && this.candidates.size()<128) {
			this.scanBudget--;
			var p=this.placements.get(this.placement);
			var pos=p.getPotentialStructureChunk(this.state.getLevelSeed(),this.rx*p.spacing(),this.rz*p.spacing());
			long key=LodForest.chunkKey(pos.x(),pos.z());
			if(within(pos.x(),pos.z(),this.cx,this.cz,this.radius) && !this.checked.contains(key) && !this.queued.contains(key)
				&& p.isStructureChunk(this.state,pos.x(),pos.z())) {this.candidates.add(key);this.queued.add(key);}
			this.advanceRegion();
		}
	}
	void retry(ChunkPos pos,boolean probe) {
		long key=LodForest.chunkKey(pos.x(),pos.z());
		if(probe) {
			this.checked.remove(key);
			if(this.candidates!=null && this.candidates.size()<128 && this.queued.add(key)) this.candidates.add(key);
		} else if(this.footprint.size()<1024) this.footprint.add(key);
	}

	void confirmed(ChunkAccess chunk) {
		this.validated++;
		for(var start:chunk.getAllStarts().values()) {
			if(!start.isValid()) continue;
			this.structures++;
			System.out.println("mcopt-lod: confirmed structure at "+chunk.getPos()+", "+start.getPieces().size()+" pieces");
			// The structure itself precedes its neighboring chunks, so a short flight still reveals useful geometry.
			for(int halo=0;halo<=1;halo++)
			for(var piece:start.getPieces()) {
				var box=piece.getBoundingBox();
				int x0=Math.max(this.cx-this.radius-16,(box.minX()>>4)-halo),x1=Math.min(this.cx+this.radius+16,(box.maxX()>>4)+halo);
				int z0=Math.max(this.cz-this.radius-16,(box.minZ()>>4)-halo),z1=Math.min(this.cz+this.radius+16,(box.maxZ()>>4)+halo);
				for(int z=z0;z<=z1 && this.footprint.size()<1024;z++) for(int x=x0;x<=x1 && this.footprint.size()<1024;x++)
					if(within(x,z,this.cx,this.cz,this.radius+16)) this.footprint.add(LodForest.chunkKey(x,z));
			}
		}
	}
	int pending() {return this.footprint.size();}
}
