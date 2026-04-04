package org;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

@Command(name = "HugeFibonacci",
        mixinStandardHelpOptions = true,
        version = "HugeFibonacci 1.0",
        description = "Calculates very large Fibonacci numbers efficiently using fast doubling and Karatsuba multiplication.")
public class HugeFibonacci implements Callable<Integer> {

    // Default values for thresholds
    private static final int DEFAULT_KARATSUBA_THRESHOLD = 200_000;
    private static final int DEFAULT_TO_STRING_THRESHOLD = 50_000;

    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final ForkJoinPool POOL = new ForkJoinPool(CORES);

    @Parameters(index = "0", description = "The nth Fibonacci number to calculate.")
    private long n;

    @Option(names = {"-s", "--save"}, description = "Save result to file.")
    private boolean saveToFile = false;

    @Option(names = {"-o", "--out"}, description = "Output filename. Defaults to fib_N.txt or fib_N.bin.")
    private String outFile = null;

    @Option(names = {"-f", "--format"}, description = "Output format (dec, bin).", defaultValue = "dec")
    private String format = "dec";

    @Option(names = {"-t", "--threshold"}, description = "Karatsuba multiplication threshold (bit length).", defaultValue = "" + DEFAULT_KARATSUBA_THRESHOLD)
    private int karatsubaThreshold = DEFAULT_KARATSUBA_THRESHOLD;

    @Option(names = {"--tostring-threshold"}, description = "Parallel toString threshold (bit length).", defaultValue = "" + DEFAULT_TO_STRING_THRESHOLD)
    private int toStringThreshold = DEFAULT_TO_STRING_THRESHOLD;

    @Option(names = {"-b", "--benchmark"}, description = "Run benchmark to find optimal thresholds for the given N.")
    private boolean benchmarkMode = false;

    // Instance methods for fib calculation to allow threshold configuration
    public BigInteger calculateFib(long n) {
        if (n < 0) throw new IllegalArgumentException("n must be non-negative");
        if (n == 0) return BigInteger.ZERO;
        if (n <= 2) return BigInteger.ONE;

        BigInteger a = BigInteger.ZERO;
        BigInteger b = BigInteger.ONE;

        for (int i = 63 - Long.numberOfLeadingZeros(n); i >= 0; i--) {
            BigInteger t1 = instanceParallelMultiply(a, b.shiftLeft(1).subtract(a));
            BigInteger t2 = instanceParallelSquare(b).add(instanceParallelSquare(a));

            a = t1;
            b = t2;

            if (((n >> i) & 1) != 0) {
                BigInteger nextA = b;
                b = a.add(b);
                a = nextA;
            }
        }
        return a;
    }

    private BigInteger instanceParallelMultiply(BigInteger x, BigInteger y) {
        if (x.bitLength() < karatsubaThreshold || y.bitLength() < karatsubaThreshold || CORES < 2) {
            return x.multiply(y);
        }
        return POOL.invoke(new KaratsubaTask(x, y, karatsubaThreshold));
    }

    private BigInteger instanceParallelSquare(BigInteger x) {
        if (x.bitLength() < karatsubaThreshold || CORES < 2) {
            return x.multiply(x);
        }
        return POOL.invoke(new KaratsubaTask(x, x, karatsubaThreshold));
    }

    static class KaratsubaTask extends RecursiveTask<BigInteger> {
        private final BigInteger x, y;
        private final int currentKaratsubaThreshold; // Use instance threshold

        KaratsubaTask(BigInteger x, BigInteger y, int currentKaratsubaThreshold) {
            this.x = x;
            this.y = y;
            this.currentKaratsubaThreshold = currentKaratsubaThreshold;
        }

        @Override
        protected BigInteger compute() {
            int n = Math.max(x.bitLength(), y.bitLength());
            if (n < currentKaratsubaThreshold) return x.multiply(y);

            int half = (n / 32 / 2) * 32;
            if (half == 0) half = (n / 2);

            BigInteger xHigh = x.shiftRight(half);
            BigInteger xLow = x.subtract(xHigh.shiftLeft(half));
            BigInteger yHigh = y.shiftRight(half);
            BigInteger yLow = y.subtract(yHigh.shiftLeft(half));

            KaratsubaTask z2 = new KaratsubaTask(xHigh, yHigh, currentKaratsubaThreshold);
            KaratsubaTask z0 = new KaratsubaTask(xLow, yLow, currentKaratsubaThreshold);
            z2.fork();
            z0.fork();

            BigInteger z1 = new KaratsubaTask(xHigh.add(xLow), yHigh.add(yLow), currentKaratsubaThreshold).compute();
            BigInteger r2 = z2.join();
            BigInteger r0 = z0.join();

            return r2.shiftLeft(2 * half)
                    .add(z1.subtract(r2).subtract(r0).shiftLeft(half))
                    .add(r0);
        }
    }

    public String instanceFastToString(BigInteger number) {
        if (number.bitLength() < toStringThreshold) return number.toString();
        return POOL.invoke(new ToStringTask(number, toStringThreshold));
    }

    static class ToStringTask extends RecursiveTask<String> {
        private final BigInteger n;
        private final int currentToStringThreshold; // Use instance threshold

        ToStringTask(BigInteger n, int currentToStringThreshold) {
            this.n = n;
            this.currentToStringThreshold = currentToStringThreshold;
        }

        @Override
        protected String compute() {
            int bitLen = n.bitLength();
            if (bitLen < currentToStringThreshold) return n.toString();

            int digits = (int) (bitLen * 0.30103) + 1;
            int half = digits / 2;
            BigInteger divisor = BigInteger.TEN.pow(half);

            BigInteger[] parts = n.divideAndRemainder(divisor);
            ToStringTask leftTask = new ToStringTask(parts[0], currentToStringThreshold);
            leftTask.fork();

            String right = parts[1].toString();
            String padding = "0".repeat(half - right.length());
            return leftTask.join() + padding + right;
        }
    }

    @Override
    public Integer call() throws Exception {
        if (benchmarkMode) {
            runBenchmark();
            return 0;
        }

        if (format.equals("txt")) { // picocli handles invalid formats, but let's be explicit
            format = "dec";
        }

        if (saveToFile && outFile == null) {
            outFile = "fib_" + n + (format.equals("bin") ? ".bin" : ".txt");
        }

        System.out.println("Cores: " + CORES + " | Memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.printf("Using Karatsuba Threshold: %d bits | ToString Threshold: %d bits%n", karatsubaThreshold, toStringThreshold);

        long start = System.nanoTime();
        BigInteger result = calculateFib(n);
        long end = System.nanoTime();

        System.out.printf("F(%d) calculated in %.4f s%n", n, (end - start) / 1e9);

        if (!saveToFile) {
            // Default behavior: print to console if not saving to file
            System.out.print("Result: ");
            long cs = System.nanoTime();
            String s = instanceFastToString(result);
            long ce = System.nanoTime();
            System.out.printf(" (ToString in %.3f s)%n", (ce - cs) / 1e9);
            System.out.println(s);
        } else {
            if (format.equals("bin")) {
                Files.write(Path.of(outFile), result.toByteArray());
                System.out.println("Saved binary to " + outFile);
            } else { // dec
                System.out.print("Converting to Decimal...");
                long cs = System.nanoTime();
                String s = instanceFastToString(result);
                long ce = System.nanoTime();
                System.out.printf(" (Done in %.3f s)%n", (ce - cs) / 1e9);
                Files.writeString(Path.of(outFile), s);
                System.out.println("Saved text to " + outFile);
            }
        }
        return 0;
    }

    private void runBenchmark() throws Exception {
        System.out.printf("Starting benchmark for N=%d on %d cores.%n", n, CORES);
        System.out.println("Finding optimal Karatsuba Threshold...");

        // Ternary Search for Karatsuba Threshold
        int bestKaratsubaThreshold = DEFAULT_KARATSUBA_THRESHOLD;
        double minKaratsubaTime = Double.MAX_VALUE;

        // Search range for Karatsuba Threshold (bit length)
        int lowK = 1_000;
        int highK = 1_000_000; // Assuming F(N) could result in operands up to 1M bits
        int bestKCandidate = DEFAULT_KARATSUBA_THRESHOLD; // Keep track of the current best candidate

        for (int i = 0; i < 20; i++) { // ~20 iterations for logarithmic reduction
            if (highK - lowK < 1000) break; // If range is small enough, break ternary search

            int m1 = lowK + (highK - lowK) / 3;
            int m2 = highK - (highK - lowK) / 3;

            // Ensure m1 and m2 are distinct and valid
            if (m1 >= m2) { // Prevents infinite loop if range gets too small
                m2 = m1 + 1;
                if (m2 > highK) m2 = highK;
            }
            if (m1 <= 0) m1 = 1; // Threshold must be at least 1
            if (m2 <= 0) m2 = 1;

            this.karatsubaThreshold = m1;
            long startM1 = System.nanoTime();
            calculateFib(n);
            double timeM1 = (System.nanoTime() - startM1) / 1e9;

            this.karatsubaThreshold = m2;
            long startM2 = System.nanoTime();
            calculateFib(n);
            double timeM2 = (System.nanoTime() - startM2) / 1e9;

            System.out.printf("  Testing K-Threshold %d: %.4f s | K-Threshold %d: %.4f s%n", m1, timeM1, m2, timeM2);

            if (timeM1 < minKaratsubaTime) {
                minKaratsubaTime = timeM1;
                bestKCandidate = m1;
            }
            if (timeM2 < minKaratsubaTime) {
                minKaratsubaTime = timeM2;
                bestKCandidate = m2;
            }

            if (timeM1 < timeM2) {
                highK = m2 - 1;
            } else {
                lowK = m1 + 1;
            }
        }

        // Refine with a linear scan around the best candidate found in the ternary search
        // Check steps of 100 bits around the narrowed range
        for (int k = Math.max(1, bestKCandidate - 500); k <= bestKCandidate + 500; k += 100) {
             this.karatsubaThreshold = k;
             long start = System.nanoTime();
             calculateFib(n);
             double time = (System.nanoTime() - start) / 1e9;
             if (time < minKaratsubaTime) {
                 minKaratsubaTime = time;
                 bestKaratsubaThreshold = k;
             }
        }
        System.out.printf("Optimal Karatsuba Threshold for N=%d: %d bits (%.4f s)%n", n, bestKaratsubaThreshold, minKaratsubaTime);

        // Set the optimal Karatsuba threshold for the next benchmark step
        this.karatsubaThreshold = bestKaratsubaThreshold;
        System.out.println("Finding optimal ToString Threshold...");

        // Ternary Search for ToString Threshold
        int bestToStringThreshold = DEFAULT_TO_STRING_THRESHOLD;
        double minToStringTime = Double.MAX_VALUE;

        // Search range for ToString Threshold (bit length)
        int lowTS = 1_000;
        int highTS = 200_000; // F(N) bitLength could be high for toString
        int bestTSCandiadte = DEFAULT_TO_STRING_THRESHOLD;

        // Calculate Fibonacci once to get the result for toString benchmarking
        BigInteger fibResult = calculateFib(n);

        for (int i = 0; i < 15; i++) { // ~15 iterations
            if (highTS - lowTS < 50) break; // If range is small enough, break ternary search

            int m1 = lowTS + (highTS - lowTS) / 3;
            int m2 = highTS - (highTS - lowTS) / 3;

            // Ensure m1 and m2 are distinct and valid
            if (m1 >= m2) { // Prevents infinite loop if range gets too small
                m2 = m1 + 1;
                if (m2 > highTS) m2 = highTS;
            }
            if (m1 <= 0) m1 = 1; // Threshold must be at least 1
            if (m2 <= 0) m2 = 1;

            this.toStringThreshold = m1;
            long startM1 = System.nanoTime();
            instanceFastToString(fibResult);
            double timeM1 = (System.nanoTime() - startM1) / 1e9;

            this.toStringThreshold = m2;
            long startM2 = System.nanoTime();
            instanceFastToString(fibResult);
            double timeM2 = (System.nanoTime() - startM2) / 1e9;

            System.out.printf("  Testing TS-Threshold %d: %.4f s | TS-Threshold %d: %.4f s%n", m1, timeM1, m2, timeM2);
            
            if (timeM1 < minToStringTime) {
                minToStringTime = timeM1;
                bestTSCandiadte = m1;
            }
            if (timeM2 < minToStringTime) {
                minToStringTime = timeM2;
                bestTSCandiadte = m2;
            }

            if (timeM1 < timeM2) {
                highTS = m2 - 1;
            } else {
                lowTS = m1 + 1;
            }
        }
        
        // Refine with a linear scan around the best candidate found in the ternary search
        // Check steps of 10 bits around the narrowed range
        for (int ts = Math.max(1, bestTSCandiadte - 25); ts <= bestTSCandiadte + 25; ts += 5) {
            this.toStringThreshold = ts;
            long start = System.nanoTime();
            instanceFastToString(fibResult);
            double time = (System.nanoTime() - start) / 1e9;
            if (time < minToStringTime) {
                minToStringTime = time;
                bestToStringThreshold = ts;
            }
        }
        
        System.out.printf("Optimal ToString Threshold for N=%d: %d bits (%.4f s)%n", n, bestToStringThreshold, minToStringTime);

        System.out.printf("%nBenchmark Results for N=%d:%n", n);
        System.out.printf("  Recommended Karatsuba Threshold: %d%n", bestKaratsubaThreshold);
        System.out.printf("  Recommended ToString Threshold: %d%n", bestToStringThreshold);
        System.out.println("You can use these values with the -t and --tostring-threshold options.");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new HugeFibonacci()).execute(args);
        System.exit(exitCode);
    }
}