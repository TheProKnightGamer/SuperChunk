// SuperChunk noise-kernel microbench (2026-08-06).
//
// Builds src/main/resources/superchunk/kernels/noise.cl together with tools/noisebench.cl
// and runs the vanilla NormalNoise chain over a corner-grid-shaped domain on the NVIDIA
// device — the same shape and the same chain the production corner kernel spends ~73% of
// GPU time in (measured: the [gpu-timeline] report).
//
// Several VARIANTS are built in ONE process and timed INTERLEAVED, round-robin, because
// this box is not quiet: back-to-back separate runs of an IDENTICAL kernel drift by >10%,
// which is larger than most of the effects being screened. Each variant also reports a
// 64-bit FNV hash over the raw bit patterns of its output, so a variant is bit-exact iff
// its hash matches variant 0's.
//
// Purpose: screen candidate noise.cl / build-flag changes in seconds instead of a
// ~3-minute server pregen each. The real gate stays the in-server parity self-test plus an
// interleaved r2048 A/B.
//
//   gcc -O2 -o noisebench noisebench.c -lOpenCL -lm
//   ./noisebench <noise.cl> <driver.cl> <reps> <octaves> <name:permfmt:opts> ...
//     permfmt: 0 = int, 1 = uchar, 2 = uchar2 pair   (must match PermFormat)
//   e.g. ./noisebench ../src/.../noise.cl noisebench.cl 16 8 \
//          "base:0:-DSC_NOINLINE" "u8:1:-DSC_NOINLINE" "r64:0:-DSC_NOINLINE -cl-nv-maxrregcount=64"
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>

#define MAXV 12
#define MAXR 64

static char *slurp(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); exit(1); }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(n + 1);
    if (fread(b, 1, n, f) != (size_t) n) { fprintf(stderr, "short read %s\n", path); exit(1); }
    b[n] = 0; fclose(f);
    return b;
}

#define CK(x) do { cl_int _e = (x); if (_e != CL_SUCCESS) { \
    fprintf(stderr, "%s:%d OpenCL err %d\n", __FILE__, __LINE__, _e); exit(1); } } while (0)

// java.util.Random, so the permutation tables are shaped like a real seed's.
typedef struct { uint64_t s; } JRand;
static void jr_seed(JRand *r, uint64_t seed) { r->s = (seed ^ 0x5DEECE66DULL) & ((1ULL << 48) - 1); }
static int32_t jr_next(JRand *r, int bits) {
    r->s = (r->s * 0x5DEECE66DULL + 0xBULL) & ((1ULL << 48) - 1);
    return (int32_t) (int64_t) (r->s >> (48 - bits));
}
static int32_t jr_int(JRand *r, int32_t bound) {
    if ((bound & -bound) == bound) return (int32_t) ((bound * (int64_t) jr_next(r, 31)) >> 31);
    int32_t bits, val;
    do { bits = jr_next(r, 31); val = bits % bound; } while (bits - val + (bound - 1) < 0);
    return val;
}
static double jr_double(JRand *r) {
    return (((int64_t) jr_next(r, 26) << 27) + jr_next(r, 27)) * 0x1.0p-53;
}

typedef struct {
    char name[64];
    char srcpath[512];
    int permfmt;
    char opts[384];
    cl_program prog;
    cl_kernel kernel;
    cl_mem perm, out;
    int fp32;
    double t[MAXR];
    uint64_t hash;
} Variant;

static int cmpd(const void *a, const void *b) {
    double x = *(const double *) a, y = *(const double *) b;
    return x < y ? -1 : (x > y ? 1 : 0);
}

int main(int argc, char **argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: noisebench noise.cl driver.cl reps octaves <name|permfmt|noise.cl|opts>...\n");
        return 2;
    }
    const char *noise_path = argv[1], *driver_path = argv[2];
    int reps = atoi(argv[3]), nOct = atoi(argv[4]);

    Variant v[MAXV]; int nv = 0;
    for (int i = 5; i < argc && nv < MAXV; i++, nv++) {
        char buf[1024]; snprintf(buf, sizeof buf, "%s", argv[i]);
        // name|permfmt|noise.cl (empty = argv[1])|opts
        char *c1 = strchr(buf, '|'); char *c2 = c1 ? strchr(c1 + 1, '|') : NULL;
        char *c3 = c2 ? strchr(c2 + 1, '|') : NULL;
        if (!c1 || !c2 || !c3) {
            fprintf(stderr, "bad variant '%s' (want name|permfmt|noise.cl|opts)\n", argv[i]); return 2;
        }
        *c1 = 0; *c2 = 0; *c3 = 0;
        snprintf(v[nv].name, sizeof v[nv].name, "%s", buf);
        snprintf(v[nv].srcpath, sizeof v[nv].srcpath, "%s", c2[1] ? c2 + 1 : noise_path);
        v[nv].permfmt = atoi(c1 + 1);
        const char *permflag = v[nv].permfmt == 2 ? " -DSC_PERM_PAIR"
                             : (v[nv].permfmt == 1 ? " -DSC_PERM_U8" : "");
        snprintf(v[nv].opts, sizeof v[nv].opts, "%s%s", c3 + 1, permflag);
        v[nv].fp32 = strstr(v[nv].opts, "USE_FP32") != NULL;
    }

    // ---- device: first NVIDIA GPU ------------------------------------------------
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
    char dname[256]; CK(clGetDeviceInfo(dev, CL_DEVICE_NAME, sizeof dname, dname, NULL));

    cl_int err;
    cl_context ctx = clCreateContext(NULL, 1, &dev, NULL, NULL, &err); CK(err);
    cl_command_queue q = clCreateCommandQueue(ctx, dev, CL_QUEUE_PROFILING_ENABLE, &err); CK(err);

    char *driver = slurp(driver_path);

    // ---- noise state: `reps` independent NormalNoise, nOct octaves per sub-sampler ----
    int slots = 2 * nOct * reps;
    int *perm = malloc(sizeof(int) * 256 * slots);
    int *active = malloc(sizeof(int) * slots);
    double *xo = malloc(sizeof(double) * slots), *yo = malloc(sizeof(double) * slots),
           *zo = malloc(sizeof(double) * slots), *amp = malloc(sizeof(double) * slots);
    JRand r; jr_seed(&r, 8675309ULL);
    for (int o = 0; o < slots; o++) {
        xo[o] = jr_double(&r) * 256.0; yo[o] = jr_double(&r) * 256.0; zo[o] = jr_double(&r) * 256.0;
        int *p = perm + o * 256;
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 0; i < 256; i++) { int j = jr_int(&r, 256 - i); int t = p[i]; p[i] = p[i + j]; p[i + j] = t; }
        active[o] = 1; amp[o] = 1.0;
    }
    double lif = 0.001953125, liv = 1.0, vfac = 0.6666666666666666;
    float lifF = (float) lif, livF = (float) liv, vfacF = (float) vfac;

    const int dimX = 5, dimY = 49, dimZ = 5, chunks = 512;
    const int n = dimX * dimY * dimZ, total = n * chunks;

    float *xoF = malloc(sizeof(float) * slots), *yoF = malloc(sizeof(float) * slots),
          *zoF = malloc(sizeof(float) * slots), *ampF = malloc(sizeof(float) * slots);
    for (int i = 0; i < slots; i++) { xoF[i] = xo[i]; yoF[i] = yo[i]; zoF[i] = zo[i]; ampF[i] = amp[i]; }

    cl_mem mAct = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(int) * slots, active, &err); CK(err);
    cl_mem mXo  = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(double) * slots, xo, &err); CK(err);
    cl_mem mYo  = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(double) * slots, yo, &err); CK(err);
    cl_mem mZo  = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(double) * slots, zo, &err); CK(err);
    cl_mem mAmp = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(double) * slots, amp, &err); CK(err);
    cl_mem mXoF = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(float) * slots, xoF, &err); CK(err);
    cl_mem mYoF = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(float) * slots, yoF, &err); CK(err);
    cl_mem mZoF = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(float) * slots, zoF, &err); CK(err);
    cl_mem mAmpF= clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, sizeof(float) * slots, ampF, &err); CK(err);

    // ---- build every variant, with its own perm encoding + output buffer -------------
    for (int i = 0; i < nv; i++) {
        char *noise = slurp(v[i].srcpath);
        char *src = malloc(strlen(noise) + strlen(driver) + 8);
        sprintf(src, "%s\n%s", noise, driver);
        const char *srcs[] = { src }; size_t lens[] = { strlen(src) };
        v[i].prog = clCreateProgramWithSource(ctx, 1, srcs, lens, &err); CK(err);
        if (clBuildProgram(v[i].prog, 1, &dev, v[i].opts, NULL, NULL) != CL_SUCCESS) {
            size_t ln = 0; clGetProgramBuildInfo(v[i].prog, dev, CL_PROGRAM_BUILD_LOG, 0, NULL, &ln);
            char *log = malloc(ln + 1);
            clGetProgramBuildInfo(v[i].prog, dev, CL_PROGRAM_BUILD_LOG, ln, log, NULL);
            log[ln] = 0;
            fprintf(stderr, "BUILD FAILED (%s, opts '%s'):\n%s\n", v[i].name, v[i].opts, log);
            return 1;
        }
        v[i].kernel = clCreateKernel(v[i].prog, "sc_bench_corner", &err); CK(err);

        int pb = v[i].permfmt == 2 ? 2 : (v[i].permfmt == 1 ? 1 : 4);
        size_t bytes = (size_t) 256 * slots * pb;
        unsigned char *pbuf = malloc(bytes);
        for (int o = 0; o < slots; o++) {
            const int *p = perm + o * 256;
            for (int j = 0; j < 256; j++) {
                size_t at = ((size_t) o * 256 + j) * pb;
                if (v[i].permfmt == 2) { pbuf[at] = (unsigned char) p[j]; pbuf[at + 1] = (unsigned char) p[(j + 1) & 255]; }
                else if (v[i].permfmt == 1) { pbuf[at] = (unsigned char) p[j]; }
                else memcpy(pbuf + at, &p[j], 4);
            }
        }
        v[i].perm = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, bytes, pbuf, &err); CK(err);
        free(pbuf);
        v[i].out = clCreateBuffer(ctx, CL_MEM_WRITE_ONLY, (size_t) total * 8, NULL, &err); CK(err);

        int a = 0; cl_kernel k = v[i].kernel;
        CK(clSetKernelArg(k, a++, sizeof(cl_mem), &v[i].perm));
        CK(clSetKernelArg(k, a++, sizeof(int), &nOct));
        CK(clSetKernelArg(k, a++, sizeof(cl_mem), &mAct));
        CK(clSetKernelArg(k, a++, sizeof(cl_mem), v[i].fp32 ? &mXoF : &mXo));
        CK(clSetKernelArg(k, a++, sizeof(cl_mem), v[i].fp32 ? &mYoF : &mYo));
        CK(clSetKernelArg(k, a++, sizeof(cl_mem), v[i].fp32 ? &mZoF : &mZo));
        CK(clSetKernelArg(k, a++, sizeof(cl_mem), v[i].fp32 ? &mAmpF : &mAmp));
        if (v[i].fp32) {
            CK(clSetKernelArg(k, a++, sizeof(float), &lifF));
            CK(clSetKernelArg(k, a++, sizeof(float), &livF));
            CK(clSetKernelArg(k, a++, sizeof(float), &vfacF));
        } else {
            CK(clSetKernelArg(k, a++, sizeof(double), &lif));
            CK(clSetKernelArg(k, a++, sizeof(double), &liv));
            CK(clSetKernelArg(k, a++, sizeof(double), &vfac));
        }
        CK(clSetKernelArg(k, a++, sizeof(int), &dimX));
        CK(clSetKernelArg(k, a++, sizeof(int), &dimY));
        CK(clSetKernelArg(k, a++, sizeof(int), &dimZ));
        CK(clSetKernelArg(k, a++, sizeof(int), &reps));
        CK(clSetKernelArg(k, a++, sizeof(cl_mem), &v[i].out));
        CK(clSetKernelArg(k, a++, sizeof(int), &total));
    }

    // ---- interleaved timing: round-robin so any drift hits every variant equally ----
    size_t gws = ((total + 63) / 64) * 64;
    const int ROUNDS = 9, WARM = 2;
    for (int round = 0; round < ROUNDS; round++) {
        for (int i = 0; i < nv; i++) {
            cl_event ev;
            CK(clEnqueueNDRangeKernel(q, v[i].kernel, 1, NULL, &gws, NULL, 0, NULL, &ev));
            CK(clWaitForEvents(1, &ev));
            cl_ulong t0, t1;
            CK(clGetEventProfilingInfo(ev, CL_PROFILING_COMMAND_START, sizeof t0, &t0, NULL));
            CK(clGetEventProfilingInfo(ev, CL_PROFILING_COMMAND_END, sizeof t1, &t1, NULL));
            clReleaseEvent(ev);
            v[i].t[round] = (t1 - t0) / 1.0e6;
        }
    }

    unsigned char *raw = malloc((size_t) total * 8);
    for (int i = 0; i < nv; i++) {
        size_t bytes = (size_t) total * (v[i].fp32 ? 4 : 8);
        CK(clEnqueueReadBuffer(q, v[i].out, CL_TRUE, 0, bytes, raw, 0, NULL, NULL));
        uint64_t h = 1469598103934665603ULL;
        for (size_t b = 0; b < bytes; b++) { h ^= raw[b]; h *= 1099511628211ULL; }
        v[i].hash = h;
    }

    printf("device=%s  reps=%d octaves=%d points=%d (%d octave-evals/point)\n",
           dname, reps, nOct, total, reps * 2 * nOct);
    printf("%-12s %-5s %9s %9s %8s  %-18s %s\n",
           "variant", "perm", "best_ms", "med_ms", "vs_ctl", "hash", "opts");
    double base = 0;
    for (int i = 0; i < nv; i++) {
        double s[MAXR]; int m = 0;
        for (int rr = WARM; rr < ROUNDS; rr++) s[m++] = v[i].t[rr];
        qsort(s, m, sizeof(double), cmpd);
        double best = s[0], med = s[m / 2];
        if (i == 0) base = med;
        char delta[32];
        if (i == 0) snprintf(delta, sizeof delta, "%s", "control");
        else snprintf(delta, sizeof delta, "%+.2f%%", 100.0 * (med - base) / base);
        printf("%-12s %-5s %9.3f %9.3f %8s  %016llx %s%s\n", v[i].name,
               v[i].permfmt == 2 ? "pair" : (v[i].permfmt == 1 ? "u8" : "int"),
               best, med, delta, (unsigned long long) v[i].hash, v[i].opts,
               (i && v[i].hash != v[0].hash) ? "   *** HASH DIFFERS FROM CONTROL ***" : "");
    }
    return 0;
}
