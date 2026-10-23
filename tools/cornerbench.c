// SuperChunk corner-program replay bench (2026-10-21).
//
// Replays the REAL cross-chunk corner program (df_batch_lattice_multichunk, and for a
// column-hoisted program the df_batch_columns_multichunk pre-pass) outside the game, on the
// real router's noise state, dumped by a server run with
//   -Dsuperchunk.gpu.dumpBatchTo=<dir>
// which writes batchN-plain.* and batchN-hoist.* (each program with its OWN noise state:
// .cl .meta .desc .factors .perm .active .xo .yo .zo .amp).
//
// Variants are timed INTERLEAVED, round-robin with a rotating start, because this box is
// shared (the same reason noisebench interleaves). Every rep runs a different batch of K
// chunks spread over a wide area, and every variant's output for that batch is hashed
// (FNV-64 over the raw output bits, double or float per the dump's realBytes): variants are bit-exact iff every rep's hash matches
// variant 0's. Times are device time from the first kernel's START to the corner kernel's
// END (OpenCL event profiling), the same span the in-game [gpu-timeline] calls "corner".
//
//   gcc -O2 -o cornerbench cornerbench.c -lOpenCL -lm
//   ./cornerbench <reps> <K> <dir>/batch1-plain <dir>/batch1-hoist ...
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define MAXV 8
#define MAXR 512

#define CK(x) do { cl_int _e = (x); if (_e != CL_SUCCESS) { \
    fprintf(stderr, "%s:%d OpenCL err %d\n", __FILE__, __LINE__, _e); exit(1); } } while (0)

static char *slurp(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(1); }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(n + 1);
    if (fread(b, 1, n, f) != (size_t) n) { fprintf(stderr, "short read %s\n", path); exit(1); }
    b[n] = 0; fclose(f);
    if (len) *len = (size_t) n;
    return b;
}

typedef struct {
    char name[128];
    char opts[1024];
    int roots, slots, realBytes;
    cl_program prog;
    cl_kernel corner, cols;
    cl_mem st[8];          // desc factors perm active xo yo zo amp
    cl_mem out, colbuf;
    double t[MAXR], tcol[MAXR];
    uint64_t hash[MAXR];
} Variant;

static const char *EXT[8] = {"desc", "factors", "perm", "active", "xo", "yo", "zo", "amp"};

static int cmpd(const void *a, const void *b) {
    double x = *(const double *) a, y = *(const double *) b;
    return x < y ? -1 : (x > y ? 1 : 0);
}

static void read_meta(Variant *v, const char *prefix) {
    char path[1024]; snprintf(path, sizeof path, "%s.meta", prefix);
    char *m = slurp(path, NULL);
    char *line = strtok(m, "\n");
    while (line) {
        if (!strncmp(line, "options=", 8)) snprintf(v->opts, sizeof v->opts, "%s", line + 8);
        else if (!strncmp(line, "roots=", 6)) v->roots = atoi(line + 6);
        else if (!strncmp(line, "columnSlots=", 12)) v->slots = atoi(line + 12);
        else if (!strncmp(line, "realBytes=", 10)) v->realBytes = atoi(line + 10);
        line = strtok(NULL, "\n");
    }
    free(m);
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: cornerbench <reps> <K> <dump-prefix>...\n");
        return 2;
    }
    int reps = atoi(argv[1]), K = atoi(argv[2]);
    if (reps > MAXR) reps = MAXR;
    int nv = argc - 3;
    if (nv > MAXV) nv = MAXV;
    Variant *v = calloc(nv, sizeof *v);

    cl_uint nplat = 0; CK(clGetPlatformIDs(0, NULL, &nplat));
    cl_platform_id *plats = calloc(nplat, sizeof *plats); CK(clGetPlatformIDs(nplat, plats, NULL));
    cl_device_id dev = NULL; char pname[256];
    for (cl_uint i = 0; i < nplat && !dev; i++) {
        CK(clGetPlatformInfo(plats[i], CL_PLATFORM_NAME, sizeof pname, pname, NULL));
        if (!strstr(pname, "NVIDIA")) continue;
        cl_uint nd = 0;
        if (clGetDeviceIDs(plats[i], CL_DEVICE_TYPE_GPU, 1, &dev, &nd) != CL_SUCCESS || nd == 0) dev = NULL;
    }
    if (!dev) { fprintf(stderr, "no NVIDIA GPU device\n"); return 1; }
    cl_int err;
    cl_context ctx = clCreateContext(NULL, 1, &dev, NULL, NULL, &err); CK(err);
    cl_command_queue q = clCreateCommandQueue(ctx, dev, CL_QUEUE_PROFILING_ENABLE, &err); CK(err);

    // Lattice: the overworld corner grid, 5 x 49 x 5 at strides 4/8/4 from y = -64.
    const int strx = 4, stry = 8, strz = 4, dx = 5, dy = 49, dz = 5;
    const int n = dx * dy * dz, plane = dx * dz;
    cl_mem originsMem = clCreateBuffer(ctx, CL_MEM_READ_ONLY, sizeof(int) * 3 * K, NULL, &err); CK(err);

    for (int i = 0; i < nv; i++) {
        const char *prefix = argv[3 + i];
        const char *base = strrchr(prefix, '/');
        snprintf(v[i].name, sizeof v[i].name, "%s", base ? base + 1 : prefix);
        v[i].realBytes = 8;   // dumps from before fp32 support were all fp64
        read_meta(&v[i], prefix);
        char path[1024]; size_t len;
        snprintf(path, sizeof path, "%s.cl", prefix);
        char *src = slurp(path, &len);
        const char *srcs[] = { src };
        v[i].prog = clCreateProgramWithSource(ctx, 1, srcs, &len, &err); CK(err);
        if (clBuildProgram(v[i].prog, 1, &dev, v[i].opts, NULL, NULL) != CL_SUCCESS) {
            size_t ln = 0; clGetProgramBuildInfo(v[i].prog, dev, CL_PROGRAM_BUILD_LOG, 0, NULL, &ln);
            char *log = malloc(ln + 1);
            clGetProgramBuildInfo(v[i].prog, dev, CL_PROGRAM_BUILD_LOG, ln, log, NULL);
            log[ln] = 0;
            fprintf(stderr, "BUILD FAILED (%s, opts '%s'):\n%s\n", v[i].name, v[i].opts, log);
            return 1;
        }
        free(src);
        for (int s = 0; s < 8; s++) {
            snprintf(path, sizeof path, "%s.%s", prefix, EXT[s]);
            char *data = slurp(path, &len);
            v[i].st[s] = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, len ? len : 1, data, &err); CK(err);
            free(data);
        }
        v[i].out = clCreateBuffer(ctx, CL_MEM_READ_WRITE, (size_t) v[i].realBytes * K * v[i].roots * n, NULL, &err); CK(err);
        v[i].corner = clCreateKernel(v[i].prog, "df_batch_lattice_multichunk", &err); CK(err);
        int total = K * n;
        int a = 0;
        for (int s = 0; s < 8; s++) CK(clSetKernelArg(v[i].corner, a++, sizeof(cl_mem), &v[i].st[s]));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(cl_mem), &originsMem));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &strx));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &stry));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &strz));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &dx));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &dz));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &n));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &v[i].roots));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(int), &total));
        CK(clSetKernelArg(v[i].corner, a++, sizeof(cl_mem), &v[i].out));
        if (v[i].slots > 0) {
            int totalCols = K * plane;
            v[i].colbuf = clCreateBuffer(ctx, CL_MEM_READ_WRITE, (size_t) v[i].realBytes * totalCols * v[i].slots, NULL, &err); CK(err);
            CK(clSetKernelArg(v[i].corner, a++, sizeof(cl_mem), &v[i].colbuf));
            v[i].cols = clCreateKernel(v[i].prog, "df_batch_columns_multichunk", &err); CK(err);
            int b = 0;
            for (int s = 0; s < 8; s++) CK(clSetKernelArg(v[i].cols, b++, sizeof(cl_mem), &v[i].st[s]));
            CK(clSetKernelArg(v[i].cols, b++, sizeof(cl_mem), &originsMem));
            CK(clSetKernelArg(v[i].cols, b++, sizeof(int), &strx));
            CK(clSetKernelArg(v[i].cols, b++, sizeof(int), &strz));
            CK(clSetKernelArg(v[i].cols, b++, sizeof(int), &dx));
            CK(clSetKernelArg(v[i].cols, b++, sizeof(int), &dz));
            CK(clSetKernelArg(v[i].cols, b++, sizeof(int), &totalCols));
            CK(clSetKernelArg(v[i].cols, b++, sizeof(cl_mem), &v[i].colbuf));
        }
        printf("built %-28s roots=%d slots=%d opts='%s'\n", v[i].name, v[i].roots, v[i].slots, v[i].opts);
    }
    for (int i = 1; i < nv; i++) {
        if (v[i].roots != v[0].roots || v[i].realBytes != v[0].realBytes) {
            fprintf(stderr, "root count or precision differs: %s\n", v[i].name); return 1;
        }
    }

    size_t outElems = (size_t) K * v[0].roots * n;
    size_t outBytes = (size_t) v[0].realBytes * outElems;
    unsigned char *host = malloc(outBytes);
    int *origins = malloc(sizeof(int) * 3 * K);
    const int wide = 8;
    for (int r = -1; r < reps; r++) {      // r = -1 is an untimed warm-up
        int rr = r < 0 ? 0 : r;
        // Batch r: K chunks, `wide` per row, at a pseudo-random region within +-1600 chunks.
        int cx0 = (int) ((rr * 7919L) % 3200) - 1600, cz0 = (int) ((rr * 104729L + 17) % 3200) - 1600;
        for (int c = 0; c < K; c++) {
            origins[c * 3 + 0] = (cx0 + c % wide) * 16;
            origins[c * 3 + 1] = -64;
            origins[c * 3 + 2] = (cz0 + c / wide) * 16;
        }
        CK(clEnqueueWriteBuffer(q, originsMem, CL_TRUE, 0, sizeof(int) * 3 * K, origins, 0, NULL, NULL));
        for (int j = 0; j < nv; j++) {
            Variant *x = &v[(j + (rr % nv)) % nv];
            cl_event e0 = NULL, e1 = NULL;
            if (x->slots > 0) {
                size_t g = ((size_t) K * plane + 63) / 64 * 64;
                CK(clEnqueueNDRangeKernel(q, x->cols, 1, NULL, &g, NULL, 0, NULL, &e0));
            }
            size_t g = ((size_t) K * n + 63) / 64 * 64;
            CK(clEnqueueNDRangeKernel(q, x->corner, 1, NULL, &g, NULL, 0, NULL, &e1));
            CK(clFinish(q));
            cl_ulong s0, s1;
            CK(clGetEventProfilingInfo(e0 ? e0 : e1, CL_PROFILING_COMMAND_START, sizeof s0, &s0, NULL));
            CK(clGetEventProfilingInfo(e1, CL_PROFILING_COMMAND_END, sizeof s1, &s1, NULL));
            double colMs = 0;
            if (e0) {
                cl_ulong c0, c1;
                CK(clGetEventProfilingInfo(e0, CL_PROFILING_COMMAND_START, sizeof c0, &c0, NULL));
                CK(clGetEventProfilingInfo(e0, CL_PROFILING_COMMAND_END, sizeof c1, &c1, NULL));
                colMs = (c1 - c0) * 1e-6;
                clReleaseEvent(e0);
            }
            clReleaseEvent(e1);
            CK(clEnqueueReadBuffer(q, x->out, CL_TRUE, 0, outBytes, host, 0, NULL, NULL));
            uint64_t h = 1469598103934665603ULL;
            for (size_t b = 0; b < outBytes; b++) { h ^= host[b]; h *= 1099511628211ULL; }
            if (r >= 0) { x->t[r] = (s1 - s0) * 1e-6; x->tcol[r] = colMs; x->hash[r] = h; }
        }
    }

    int mismatchReps = 0;
    for (int r = 0; r < reps; r++) {
        for (int i = 1; i < nv; i++) {
            if (v[i].hash[r] != v[0].hash[r]) {
                if (mismatchReps < 5) {
                    printf("MISMATCH rep %d: %s %016llx vs %s %016llx\n", r, v[i].name,
                           (unsigned long long) v[i].hash[r], v[0].name, (unsigned long long) v[0].hash[r]);
                }
                mismatchReps++;
                break;
            }
        }
    }
    printf("\nK=%d chunks/batch, %d reps (each a different batch), device ms per batch:\n", K, reps);
    double base = 0;
    for (int i = 0; i < nv; i++) {
        double s[MAXR]; memcpy(s, v[i].t, sizeof(double) * reps);
        qsort(s, reps, sizeof(double), cmpd);
        double med = s[reps / 2], p10 = s[reps / 10], p90 = s[(reps * 9) / 10];
        if (i == 0) base = med;
        memcpy(s, v[i].tcol, sizeof(double) * reps);
        qsort(s, reps, sizeof(double), cmpd);
        printf("  %-28s median %8.3f  p10 %8.3f  p90 %8.3f  per chunk %.4f ms  (%+.1f%% vs %s)  column pass %.3f\n",
               v[i].name, med, p10, p90, med / K, 100.0 * (med - base) / base, v[0].name, s[reps / 2]);
    }
    printf("bit-exact: %s (%d of %d reps differ)\n", mismatchReps ? "NO" : "yes", mismatchReps, reps);
    return mismatchReps ? 3 : 0;
}
