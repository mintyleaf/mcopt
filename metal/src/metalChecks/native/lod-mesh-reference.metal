// Frozen pre-partition vertex path for GPU output equivalence checks.
static inline MeshVOut reference_mesh_vs2(uint vid, uint iid, constant CompFrame& f,
                             device const MeshInstance* inst, device const uint* arena,
                             device const uint* args, device const uint4* survivors,
                             device const uint4* table, device const uint* geom, device const uint* crowns,
                             constant MeshFrame& mf) {
    uint sidx = iid * MESH_DRAW_Q + (vid >> 2);
    if (sidx >= args[ARGS_SURV]) {
        MeshVOut o;
        o.position = float4(0.0, 0.0, -2.0, 1.0);
        o.info = 0u;
        return o;
    }
    uint4 r = survivors[sidx];
    int L = int((r.w >> 20) & 15u);
    if ((r.w & (1u << 24)) != 0u) {
        MeshInstance mi = inst[r.z];
        if (!meshResolve(mf, table, mi)) {
            MeshVOut o;
            o.position = float4(0.0, 0.0, -2.0, 1.0);
            o.info = 0u;
            return o;
        }
#ifdef SEAM_HANDOFF
        if (r.x >= (mi.a.y & 255u)) return meshHandoffHidden();
        uint w0 = arena[(mi.a.x + r.x) * 2u];
        if (meshHandoffQuad(f, w0, int(mi.a.z), int(mi.a.w), int((mi.a.y >> 8) & 15u), true, mi.b) != lodHandoffPass)
            return meshHandoffHidden();
#endif
        return meshCorner(f, mi, arena, r.x * 4u + (vid & 3u));
    }
    int tz = int(r.w << 12) >> 12;
#ifdef SEAM_HANDOFF
    if (meshHandoffQuad(f, r.x, int(r.z), tz, L, false, int4(0)) != lodHandoffPass) return meshHandoffHidden();
#endif
    uint w1 = r.y;
    if ((r.x >> 31) != 0u && (vid & 3u) < 2u) {
        // a skirt's foot: down to the surface across its seam
        int y0 = int(w1 & 0xFFFu) - 512;
        y0 = min(y0, meshSkirtFloor(mf, table, geom, crowns, r.x, int(r.z), tz, L));
        w1 = (w1 & ~0xFFFu) | uint(y0 + 512);
    }
    MeshVOut o = meshQuadCorner(f, r.x, w1, int(r.z), tz, L, false, int4(0), vid & 3u, sidx);
    if (((r.w >> 25) & 3u) != 0u) o.info = 7u | ((r.w >> 25) & 3u) << 3;
    return o;
}

