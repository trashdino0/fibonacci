package org;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

@Command(name = "HugeFibonacciMutable", mixinStandardHelpOptions = true)
public class HugeFibonacciMutable implements Runnable {

    private static final int TO_STRING_THRESHOLD = 50_000;
    private static final ForkJoinPool POOL = new ForkJoinPool();

    // OPTIMIZATION: Precomputed Base Cases (First 16 Fibs)
    private static final int[] FIB_BASE = {
            0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987
    };

    @Parameters(index = "0", description = "The nth Fibonacci number to calculate.")
    private int n;

    @Option(names = {"-a", "--average"}, description = "Number of runs for averaging (default = 1)")
    private int runs = 1;

    @Option(names = {"-w", "--warmup"}, description = "Warmup: RUNS,N (default 50,10000)", arity = "0..1", fallbackValue = "50,10000")
    private String warmupConfig = null;

    @Option(names = {"-p", "--print"}, description = "Print F(n) to the console after calculation.")
    private boolean print = false;

    @Option(names = {"-s", "--save"}, description = "Save F(n) as decimal text to the given file.")
    private String saveFile = null;

    public static void main(String[] args) {
        new CommandLine(new HugeFibonacciMutable()).execute(args);
    }

    @Override
    public void run() {
        if (warmupConfig != null) {
            String[] parts = warmupConfig.split(",");
            int warmupRuns = Integer.parseInt(parts[0].trim());
            int warmupN = Integer.parseInt(parts[1].trim());
            System.out.printf("Warming up: %d × F(%d)…%n", warmupRuns, warmupN);
            for (int w = 0; w < warmupRuns; w++) computeFib(warmupN);
            System.out.println("Warmup complete.");
        }

        System.out.println("Calculating F(" + n + ") with Parallel Mutable NTT...");

        DescriptiveStatistics stats = new DescriptiveStatistics();
        MutableBigInt lastResult = null;

        for (int x = 0; x < runs; x++) {
            long start = System.nanoTime();
            lastResult = computeFib(n);
            long end = System.nanoTime();

            double secs = (end - start) / 1e9;
            stats.addValue(secs);
            if (runs > 1) System.out.printf("  Run %d: %.4f s%n", x + 1, secs);
        }

        double mean = stats.getMean();
        if (runs > 1) {
            TDistribution td = new TDistribution(stats.getN() - 1);
            double crit = td.inverseCumulativeProbability(1.0 - 0.05 / 2.0);
            double moe = crit * (stats.getStandardDeviation() / Math.sqrt(stats.getN()));
            System.out.println("\n--- Statistical Analysis ---");
            System.out.printf("Runs:     %d%n", stats.getN());
            System.out.printf("Mean:     %.4f s%n", mean);
            System.out.printf("95%% CI:  [%.4f s, %.4f s]%n", mean - moe, mean + moe);
            System.out.printf("StdDev:   %.4f s%n", stats.getStandardDeviation());
            System.out.printf("Skewness: %.4f%n", stats.getSkewness());
        } else {
            System.out.printf("F(%d) calculated in %.4f s%n", n, mean);
        }

        if ((print || saveFile != null) && lastResult != null) {
            System.out.print("Converting to decimal...");
            long cs = System.nanoTime();
            String s = parallelToString(lastResult.toBigInteger());
            long ce = System.nanoTime();
            System.out.printf(" (%.3f s)%n", (ce - cs) / 1e9);

            if (print) System.out.println(s);

            if (saveFile != null) {
                try {
                    Files.writeString(Path.of(saveFile), s);
                    System.out.println("Saved to " + saveFile);
                } catch (Exception e) {
                    System.err.println("Failed to save: " + e.getMessage());
                }
            }
        }
    }

    private String parallelToString(BigInteger value) {
        return POOL.invoke(new ToStringTask(value));
    }

    static class ToStringTask extends RecursiveTask<String> {
        private final BigInteger n;
        ToStringTask(BigInteger n) { this.n = n; }

        @Override
        protected String compute() {
            if (n.bitLength() < TO_STRING_THRESHOLD) return n.toString();
            int digits = (int)(n.bitLength() * 0.30103) + 1;
            int half = digits / 2;
            BigInteger divisor = BigInteger.TEN.pow(half);
            BigInteger[] parts = n.divideAndRemainder(divisor);
            ToStringTask leftTask = new ToStringTask(parts[0]);
            leftTask.fork();
            String right = parts[1].toString();
            String padding = "0".repeat(half - right.length());
            return leftTask.join() + padding + right;
        }
    }

    private MutableBigInt computeFib(int target) {
        // OPTIMIZATION: Early exit for base cases
        if (target < FIB_BASE.length) {
            MutableBigInt z = new MutableBigInt(1);
            z.fromLong(FIB_BASE[target]);
            return z;
        }

        int size = (int)(target * 0.022) + 256;
        int bufSize = Integer.highestOneBit(size * 2 - 1) << 1;

        Workspace ws = new Workspace();
        int msb = 31 - Integer.numberOfLeadingZeros(target);

        // OPTIMIZATION: Skip the first 4 loop iterations by seeding from the lookup table
        int prefixBits = Math.min(msb + 1, 4);
        int prefixVal = target >>> (msb + 1 - prefixBits);

        MutableBigInt a = ws.getMutable(bufSize);  a.fromLong(FIB_BASE[prefixVal]);
        MutableBigInt b = ws.getMutable(bufSize);  b.fromLong(FIB_BASE[prefixVal + 1]);

        for (int i = msb - prefixBits; i >= 0; i--) {
            MutableBigInt newA = ws.getMutable(bufSize);
            MutableBigInt newB = ws.getMutable(bufSize);

            if (a.length < MutableBigInt.NTT_THRESHOLD &&
                    b.length < MutableBigInt.NTT_THRESHOLD) {
                schoolbookDouble(a, b, newA, newB, ws);
            } else {
                MutableBigInt.fibDoubleNTT(a, b, newA, newB, ws);
            }

            ws.release(a); ws.release(b);
            a = newA; b = newB;

            if (((target >> i) & 1) != 0) {
                MutableBigInt nextB = ws.getMutable(bufSize);
                nextB.copy(a);
                nextB.add(b);

                MutableBigInt oldA = a;
                a = b;
                b = nextB;
                ws.release(oldA);
            }
        }
        return a;
    }

    private static void schoolbookDouble(MutableBigInt a, MutableBigInt b,
                                         MutableBigInt outA, MutableBigInt outB, Workspace ws) {
        MutableBigInt a2 = ws.getMutable(a.mag.length);
        a.multiply(a, a2, ws);

        MutableBigInt b2 = ws.getMutable(b.mag.length);
        b.multiply(b, b2, ws);

        outB.copy(a2);
        outB.add(b2);

        MutableBigInt ab = ws.getMutable(a.mag.length + b.mag.length);
        a.multiply(b, ab, ws);
        ab.shiftLeftOne();  // OPTIMIZATION: Use specialized in-place shift
        ab.subtract(a2);
        outA.copy(ab);

        ws.release(a2); ws.release(b2); ws.release(ab);
    }
}