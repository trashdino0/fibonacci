package org;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Number-theoretic transform over three NTT-friendly primes.
 *
 * Optimisations vs original
 * ─────────────────────────
 * 1. Barrett reduction (double approximation) replaces every `% p` in the
 *    butterfly loop.  Hardware 64-bit idiv ≈ 20-40 cycles; Barrett ≈ 5 cycles.
 *    For a,b < p < 2^30 the product a*b < 2^60 fits in a signed long, so no
 *    128-bit arithmetic is required.  The double quotient approximation is
 *    within ±1 of the true quotient (verified below), so a single conditional
 *    add/subtract corrects it.
 *
 *    Error analysis: x = a*b ≤ 2^60.  double(x) rounds with error ≤ x·2^-52
 *    ≈ 256.  double(x)·pInv then has absolute error ≤ 256/p < 1e-6, so
 *    (long)(x*pInv) differs from ⌊x/p⌋ by at most 1.  One fixup suffices.
 *
 * 2. Precomputed root-of-unity tables in "layered" layout:
 *      roots[1]          = 1
 *      roots[k .. 2k-1]  = twiddle factors for DIT stage with half-width k
 *    The inner butterfly loop reads roots[len], roots[len+1], … sequentially,
 *    which is cache-friendly.  The incremental update `w = w*wlen % p` that
 *    was previously one extra modmul per butterfly is eliminated entirely.
 *
 * 3. Root tables are keyed by (logN, primeIndex) and lazily cached via
 *    ConcurrentHashMap so they are shared across all fast-doubling iterations
 *    and across calls with the same NTT size.
 */
public final class NTT {

    // ── Three NTT-friendly primes ──────────────────────────────────────────
    public static final long P1 = 998244353L;   // 119 · 2^23 + 1
    public static final long P2 = 1004535809L;  // 479 · 2^21 + 1
    public static final long P3 = 469762049L;   //   7 · 2^26 + 1
    // All three share primitive root 3.
    static final long G1 = 3L, G2 = 3L, G3 = 3L;

    static final long[]   PRIMES     = { P1, P2, P3 };
    static final long[]   GENS       = { G1, G2, G3 };

    /** 1.0/p for Barrett reduction — computed once as a double constant. */
    public static final double INV_P1 = 1.0 / P1;
    public static final double INV_P2 = 1.0 / P2;
    public static final double INV_P3 = 1.0 / P3;
    static final double[] INV_PRIMES = { INV_P1, INV_P2, INV_P3 };

    // ── Root-table caches ──────────────────────────────────────────────────
    // Key = logN * 3 + primeIndex (both logN and primeIndex are small).
    private static final ConcurrentHashMap<Integer, long[]> FWD_CACHE  = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, long[]> INV_CACHE  = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long>   NINV_CACHE = new ConcurrentHashMap<>();

    private NTT() {}

    // ══════════════════════════════════════════════════════════════════════
    //  Barrett modular multiplication
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns (a * b) mod p using Barrett reduction.
     *
     * Preconditions: 0 ≤ a, b < p < 2^30  →  a*b < 2^60 < Long.MAX_VALUE.
     */
    public static long mulmod(long a, long b, long p, double pInv) {
        long x = a * b;
        long q = (long)(x * pInv);   // approximate quotient, off by at most 1
        long r = x - q * p;
        if (r < 0L)  return r + p;
        if (r >= p)  return r - p;
        return r;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Root-of-unity table construction
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build the layered root table for a DIT NTT of size 2^logN.
     *
     * Layout (index i ∈ [1, 2^logN)):
     *   roots[1]       = 1
     *   for each k = 2, 4, 8, …, n/2:
     *     roots[k]     = roots[k/2]          (inherit from coarser level)
     *     roots[k+j]   = roots[k+j-1] · e    for j = 1 … k-1
     *   where e = g^{(p-1)/(2k)} is the primitive (2k)-th root of unity.
     *
     * With this layout the butterfly inner loop accesses
     *   roots[len], roots[len+1], …, roots[len + len - 1]
     * sequentially, which is optimal for the hardware prefetcher.
     *
     * For the inverse NTT pass g with g^{-1} (same table structure).
     */
    private static long[] buildRoots(int logN, int pi, boolean inverse) {
        int    n   = 1 << logN;
        long   p   = PRIMES[pi];
        long   g   = GENS[pi];
        if (inverse) g = power(g, p - 2L, p);
        double pI  = INV_PRIMES[pi];

        // Minimum table size is 2 (roots[0] unused, roots[1] = 1).
        long[] rt = new long[Math.max(n, 2)];
        rt[1] = 1L;
        for (int k = 2; k < n; k <<= 1) {
            long e = power(g, (p - 1L) / (2L * k), p);
            for (int i = k; i < 2 * k; i++) {
                rt[i] = ((i & 1) == 0) ? rt[i >> 1]
                                        : mulmod(rt[i - 1], e, p, pI);
            }
        }
        return rt;
    }

    private static int cacheKey(int logN, int pi) { return logN * 3 + pi; }

    /** Forward root table for given NTT size (2^logN) and prime index. */
    public static long[] fwdRoots(int logN, int pi) {
        return FWD_CACHE.computeIfAbsent(cacheKey(logN, pi),
                k -> buildRoots(logN, pi, false));
    }

    /** Inverse root table (using g^{-1} as generator). */
    public static long[] invRoots(int logN, int pi) {
        return INV_CACHE.computeIfAbsent(cacheKey(logN, pi),
                k -> buildRoots(logN, pi, true));
    }

    /** n^{-1} mod p, cached. */
    public static long nInv(int logN, int pi) {
        return NINV_CACHE.computeIfAbsent(cacheKey(logN, pi),
                k -> power(1L << logN, PRIMES[pi] - 2L, PRIMES[pi]));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  In-place DIT NTT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Forward in-place NTT.  a[0..2^logN) is transformed in-place.
     *
     * @param a     array of length exactly 2^logN, values in [0, p)
     * @param logN  log2 of array length (must be ≥ 1)
     * @param pi    prime index: 0 → P1, 1 → P2, 2 → P3
     */
    public static void nttForward(long[] a, int logN, int pi) {
        if (logN == 0) return;
        long   p   = PRIMES[pi];
        double pI  = INV_PRIMES[pi];
        long[] rt  = fwdRoots(logN, pi);
        int    n   = 1 << logN;

        bitRev(a, n);

        for (int len = 1; len < n; len <<= 1) {
            int two = len << 1;
            for (int i = 0; i < n; i += two) {
                for (int j = 0; j < len; j++) {
                    long u = a[i + j];
                    long v = mulmod(a[i + j + len], rt[len + j], p, pI);
                    a[i + j]       = (u + v >= p) ? u + v - p : u + v;
                    a[i + j + len] = (u  <  v)    ? u - v + p : u - v;
                }
            }
        }
    }

    /**
     * Inverse in-place NTT (includes 1/n scaling).
     */
    public static void nttInverse(long[] a, int logN, int pi) {
        if (logN == 0) return;
        long   p   = PRIMES[pi];
        double pI  = INV_PRIMES[pi];
        long[] rt  = invRoots(logN, pi);
        long   ni  = nInv(logN, pi);
        int    n   = 1 << logN;

        bitRev(a, n);

        for (int len = 1; len < n; len <<= 1) {
            int two = len << 1;
            for (int i = 0; i < n; i += two) {
                for (int j = 0; j < len; j++) {
                    long u = a[i + j];
                    long v = mulmod(a[i + j + len], rt[len + j], p, pI);
                    a[i + j]       = (u + v >= p) ? u + v - p : u + v;
                    a[i + j + len] = (u  <  v)    ? u - v + p : u - v;
                }
            }
        }

        // Scale by n^{-1} (fused into a single pass after the butterfly)
        for (int i = 0; i < n; i++) a[i] = mulmod(a[i], ni, p, pI);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Bit-reversal permutation (amortised O(n) via binary-counter trick)
    // ══════════════════════════════════════════════════════════════════════

    static void bitRev(long[] a, int n) {
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { long t = a[i]; a[i] = a[j]; a[j] = t; }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Arithmetic utilities
    // ══════════════════════════════════════════════════════════════════════

    public static long power(long base, long exp, long mod) {
        long res = 1L;
        base %= mod;
        while (exp > 0L) {
            if ((exp & 1L) != 0L) res = res * base % mod;
            base = base * base % mod;
            exp >>= 1;
        }
        return res;
    }

    public static long modInverse(long n, long mod) {
        return power(n, mod - 2L, mod);
    }
}
