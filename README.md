# Fibonacci GMP

A high-performance Java library for computing colossal Fibonacci numbers using number-theoretic transforms (NTT), fast doubling, and aggressive parallelism. Inspired by the GMP (GNU Multiple Precision) approach to big-integer arithmetic.

Computing F(10⁷) completes in seconds on modern hardware. The result has over 2 million decimal digits.

## Features

- **Fast doubling** — O(log n) Fibonacci computation using the identities:
  - `F(2k) = F(k) · [2·F(k+1) − F(k)]`
  - `F(2k+1) = F(k+1)² + F(k)²`
- **NTT-based multiplication** — Convolution via three-modulus number-theoretic transform (NTT) with Garner's CRT for exact integer reconstruction
- **Parallel NTT** — Three NTT prime tracks run concurrently via `ForkJoinPool`, with nested parallelism inside each track (forked forward/inverse transforms)
- **Schoolbook fallback** — O(n²) multiplication for operands below 32 limbs (≈ 4,096 bits) to avoid NTT overhead on small values
- **Parallel decimal conversion** — Recursive `ForkJoinTask` that splits the `BigInteger` at the decimal midpoint, forking the high half while converting the low half inline
- **Buffer pooling** — `Workspace` object pool recycles `int[]`, `long[]`, and `MutableBigInt` instances by power-of-two size class, eliminating GC pressure during hot loops
- **Warmup & benchmarking** — Built-in JIT warmup cycle and statistical timing with 95% confidence intervals, mean, stddev, and skewness via Apache Commons Math
- **Seed optimization** — Fast-doubling loop seeds from a precomputed lookup table for the first 4 bits, skipping unnecessary iterations
- **picocli CLI** — Flags for averaging, warmup, printing, and file output

## Requirements

- Java 21+
- Apache Maven (for building)

## Building

```bash
mvn clean package
```

Produces `target/fibonacci-1.0-SNAPSHOT.jar`.

## Usage

```bash
java -jar target/fibonacci-1.0-SNAPSHOT.jar [options] <n>
```

### Positional arguments

| Argument | Description |
|----------|-------------|
| `n`      | Index of the Fibonacci number to compute |

### Options

| Flag | Description |
|------|-------------|
| `-a N`, `--average N` | Run N iterations and print statistics (mean, 95% CI, stddev, skewness) |
| `-w [RUNS,N]`, `--warmup [RUNS,N]` | JIT warmup: run `RUNS` iterations of `F(N)` before timing. Default when bare flag: `50,10000` |
| `-p`, `--print` | Print the full decimal result to stdout |
| `-s FILE`, `--save FILE` | Save the decimal result to a file |
| `-h`, `--help` | Show help |
| `-V`, `--version` | Print version |

### Examples

```bash
# Compute F(1,000,000)
java -jar target/fibonacci-1.0-SNAPSHOT.jar 1000000

# Benchmark with 10 runs and warmup
java -jar target/fibonacci-1.0-SNAPSHOT.jar 1000000 -a 10 -w 20,10000

# Print the result and save to file
java -jar target/fibonacci-1.0-SNAPSHOT.jar 1000000 -p -s fib.txt
```

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                  HugeFibonacciMutable                │
│  (picocli CLI, fast-doubling loop, warmup, stats)   │
└──────────┬────────────────────────────────┬──────────┘
           │                                │
           ▼                                ▼
┌──────────────────────┐    ┌──────────────────────────┐
│    MutableBigInt     │    │    ToStringTask          │
│  (limb-based big int)│    │  (parallel BigInteger →  │
│                      │    │   decimal via split)     │
│  Schoolbook (n < 32) │    └──────────────────────────┘
│  NTT-based (n ≥ 32)  │
│  fibDoubleNTT (3×)   │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐    ┌──────────────────────────┐
│    NTT (×3 primes)   │    │     Workspace            │
│  Forward/inverse DIT │    │  (int[], long[], MBI     │
│  Barrett reduction   │    │   pools by pow2 size)    │
│  Garner's CRT        │    └──────────────────────────┘
└──────────────────────┘
```

### Fast Doubling Algorithm

The core loop in `computeFib()` iterates over the bits of `n` from MSB to LSB:

1. **Seed** from a lookup table using the first 4 bits of `n`
2. For each remaining bit:
   - Apply the doubling transform `(F(k), F(k+1)) → (F(2k), F(2k+1))`
   - If the current bit is 1, advance: `(F(k), F(k+1)) → (F(k+1), F(k)+F(k+1))`

The doubling step uses either `schoolbookDouble` (for small values) or `fibDoubleNTT` (for large values).

### Number-Theoretic Transform (NTT)

Multiplication uses convolution via NTT over three primes, each < 2³⁰ so pairwise products fit in a 64-bit `long`:

| Prime | Value | Factorization | Max transform length |
|-------|-------|---------------|---------------------|
| P₁    | 998,244,353 | 119 × 2²³ + 1 | 2²³ |
| P₂    | 1,004,535,809 | 479 × 2²¹ + 1 | 2²¹ |
| P₃    | 469,762,049 | 7 × 2²⁶ + 1 | 2²⁶ |

All three use primitive root **3**. The transforms run in parallel across primes via `RecursiveAction.invokeAll`. Inside each prime track, forward and inverse NTTs are further parallelized through forked subtasks.

The layered root tables are built lazily and cached in `ConcurrentHashMap` for reuse across calls.

### Parallel ToString

The `ToStringTask` recursively splits a `BigInteger`:

```
digits = (int)(n.bitLength() * 0.30103) + 1
half = digits / 2
divisor = 10^half
[high, low] = n.divideAndRemainder(divisor)
```

The high half is forked as a subtask, the low half is converted inline, and results are concatenated with zero-padding. Below a threshold of 50,000 bits (~15,000 decimal digits), the conversion falls through to `BigInteger.toString()` directly.

### Thresholds

| Constant | Value | Purpose |
|----------|-------|---------|
| NTT_THRESHOLD | 32 limbs | Use schoolbook below this |
| TO_STRING_THRESHOLD | 50,000 bits | Stop splitting below this |
| Limbs per index | ~0.022 | `log₁₀(φ) / 32` for size estimation |
| Digits per bit | ~0.30103 | `log₁₀(2)` for decimal estimation |

## Performance

The implementation scales efficiently with the number of cores. The three-modulus NTT design means 6–12 threads can be kept busy simultaneously during multiplication, while the parallel toString makes full use of all available processors during conversion.

Example wall-clock times (12-core AMD Ryzen 9, JDK 21):

| n | Decimal digits | Compute time |
|---|---------------|--------------|
| 10⁵ | ~20,899 | ~0.03 s |
| 10⁶ | ~208,988 | ~0.4 s |
| 10⁷ | ~2,089,877 | ~6 s |

*(YMMV based on CPU, memory allocation, and JVM tuning.)*

## Implementation Details

### Limb Representation

`MutableBigInt` stores unsigned 32-bit limbs in little-endian order (`mag[0]` = LSW). Arrays are pre-allocated to the next power of two for NTT alignment. The limb count tracks the high-water mark of used words.

### Three-Product NTT Double

`fibDoubleNTT` computes `A = a²`, `B = b²`, and `C = a·b` simultaneously using the same forward transforms of `a` and `b`. For each prime:
1. Forward NTT of `fa` and `fb`
2. Pointwise compute `rA2[i] = fa[i]²`, `rB2[i] = fb[i]²`, `rAB[i] = fa[i]·fb[i]`
3. Three inverse NTTs (two forked, one inline)

Then `F(2k+1) = A + B` and `F(2k) = 2C − A`.

### Barrett Reduction

Uses `double`-approximated quotient for fast modular multiplication:

```
q = floor(a * b * (1/p))
r = a * b − q * p
if r < 0: r += p
if r >= p: r -= p
```

### Workspace Pooling

Buffers are grouped by power-of-two capacity (indices 0–31). `getIntArray(size)` pops from the pool or allocates `new int[1 << idx]`, zeroing only the first `size` elements rather than the full capacity.

## References

- [Fast Doubling](https://cp-algorithms.com/algebra/fibonacci-numbers.html) — CP-Algorithms
- [NTT (Number Theoretic Transform)](https://cp-algorithms.com/algebra/fft.html) — CP-Algorithms
- [Garner's Algorithm (CRT)](https://cp-algorithms.com/algebra/chinese-remainder-theorem.html) — CP-Algorithms
- [GMP: GNU Multiple Precision Arithmetic Library](https://gmplib.org/)
