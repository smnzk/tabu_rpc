package org.example.worker;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainThreads {

    // === From helpers.h ===
    static final int N = 100;
    static final double EPSILON = 1e-12;

    @FunctionalInterface
    interface ObjectiveFunction {
        double apply(double[] x, int n);
    }

    // Matches your "avoid false sharing" intent
    static final class ThreadResult {
        volatile double current_best_f;
        volatile int best_move_index;
        volatile int best_move_direction;
        volatile double best_candidate_val;
    }

    // Very low-overhead barrier for small thread counts (<=4)
    static final class SpinBarrier {
        private final int parties;
        private final AtomicInteger arrived = new AtomicInteger(0);
        private final AtomicInteger phase = new AtomicInteger(0);

        SpinBarrier(int parties) { this.parties = parties; }

        // localPhase is per-thread counter, caller increments each await
        void await(int localPhase) {
            if (parties == 1) return;

            int p = localPhase;
            if (arrived.incrementAndGet() == parties) {
                arrived.set(0);
                phase.incrementAndGet();
                return;
            }
            int target = p + 1;
            while (phase.get() < target) {
                Thread.onSpinWait();
            }
        }
    }

    static final class SharedData {
        final int numThreads;
        final int maxIter;
        final double[] lower = new double[N];
        final double[] upper = new double[N];
        final double step;
        final int tabuTenure;

        int stagnation;
        final int patience;

        final double[] s = new double[N];
        double fs;
        final double[] best = new double[N];
        final double[] prevBest = new double[N];
        double fBest;

        final int[] tabuExpireMinus = new int[N];
        final int[] tabuExpirePlus  = new int[N];

        final ThreadResult[] results;
        final SpinBarrier barrier;
        final ObjectiveFunction f;

        volatile int stop_flag;

        SharedData(int numThreads, int maxIter, double step, int tabuTenure, ObjectiveFunction f) {
            this.numThreads = numThreads;
            this.maxIter = maxIter;
            this.step = step;
            this.tabuTenure = tabuTenure;
            this.f = f;
            this.fBest = Double.POSITIVE_INFINITY;
            this.stagnation = 0;
            this.patience = 200;

            this.results = new ThreadResult[numThreads];
            for (int i = 0; i < numThreads; i++) results[i] = new ThreadResult();

            this.barrier = new SpinBarrier(numThreads);
        }
    }

    static final class Worker implements Runnable {
        final int rank;
        final SharedData sd;

        Worker(int rank, SharedData sd) {
            this.rank = rank;
            this.sd = sd;
        }

        @Override
        public void run() {
            final int T = sd.numThreads;

            // Exact same chunking as C
            final int base = N / T;
            final int rem  = N % T;
            final int chunk_start = rank * base + (rank < rem ? rank : rem);
            final int chunk_size  = base + (rank < rem ? 1 : 0);
            final int chunk_end   = chunk_start + chunk_size;

            // Reused per-thread candidate buffer (no allocations per iteration)
            final double[] candidate = new double[N];

            int ph = 0; // local barrier phase counter (two awaits per iter)

            for (int k = 1; k <= sd.maxIter; k++) {
                ThreadResult tr = sd.results[rank];
                tr.current_best_f = Double.POSITIVE_INFINITY;
                tr.best_move_index = -1;

                // Same as memcpy(candidate, sd->s, ...)
                System.arraycopy(sd.s, 0, candidate, 0, N);

                for (int i = chunk_start; i < chunk_end; i++) {
                    final double old_val = sd.s[i];

                    // dir_idx: 0 -> -1, 1 -> +1 (exact order)
                    for (int dir_idx = 0; dir_idx < 2; dir_idx++) {
                        final int dir = (dir_idx == 0) ? -1 : 1;

                        double new_val = old_val + dir * sd.step;

                        // Clamp (exact logic)
                        if (new_val < sd.lower[i]) new_val = sd.lower[i];
                        if (new_val > sd.upper[i]) new_val = sd.upper[i];

                        candidate[i] = new_val;
                        final double fc = sd.f.apply(candidate, N);

                        final boolean isTabu = (dir == -1)
                                ? (k < sd.tabuExpireMinus[i])
                                : (k < sd.tabuExpirePlus[i]);

                        final boolean aspiration = (fc < sd.fBest);

                        if (!isTabu || aspiration) {
                            if (fc < tr.current_best_f) {
                                tr.current_best_f = fc;
                                tr.best_move_index = i;
                                tr.best_move_direction = dir;
                                tr.best_candidate_val = new_val;
                            }
                        }

                        candidate[i] = old_val; // reset
                    }
                }

                // Barrier 1 (match pthread_barrier_wait)
                sd.barrier.await(ph); ph++;

                if (rank == 0) {
                    int global_best_idx = -1;
                    double global_best_f = Double.POSITIVE_INFINITY;

                    for (int t = 0; t < T; t++) {
                        ThreadResult rt = sd.results[t];
                        if (rt.best_move_index != -1 && rt.current_best_f < global_best_f) {
                            global_best_f = rt.current_best_f;
                            global_best_idx = t;
                        }
                    }

                    if (global_best_idx != -1) {
                        ThreadResult res = sd.results[global_best_idx];
                        int move_var = res.best_move_index;

                        // Update global state (exact)
                        sd.s[move_var] = res.best_candidate_val;
                        sd.fs = res.current_best_f;

                        if (sd.fs < sd.fBest) {
                            sd.fBest = sd.fs;
                            System.arraycopy(sd.s, 0, sd.best, 0, N);
                        }

                        // Update tabu list (exact direction mapping)
                        if (res.best_move_direction == -1) {
                            sd.tabuExpirePlus[move_var] = k + sd.tabuTenure;
                        } else {
                            sd.tabuExpireMinus[move_var] = k + sd.tabuTenure;
                        }
                    }

                    double d = diffInfNorm(sd.best, sd.prevBest, N);

                    if (d < EPSILON) {
                        sd.stagnation++;
                    } else {
                        sd.stagnation = 0;
                        System.arraycopy(sd.best, 0, sd.prevBest, 0, N);
                    }

                    if (sd.stagnation >= sd.patience) {
                        System.out.println("Stagnation criterion met at iteration " + k);
                        sd.stagnation = 1;
                        sd.stop_flag = 1;
                    }
                }

                // Barrier 2 (match pthread_barrier_wait)
                sd.barrier.await(ph); ph++;

                if (sd.stop_flag != 0) return;
            }
        }
    }

    // === Port of tabuSearch(...) with same prints/params ===
    static void tabuSearch(
            int numThreads,
            int tabuTenure,
            double[] lower,
            double[] upper,
            double[] initial_x,
            ObjectiveFunction f,
            int maxIter,
            double step
    ) {
        System.out.println("==========================");
        System.out.println("Dimension = " + N);
        System.out.println("Max iter = " + maxIter);
        System.out.println("Step = " + step);
        System.out.println("Tab tenure = " + tabuTenure);
        System.out.println("Threads = " + numThreads);
        System.out.println("==========================");

        SharedData sd = new SharedData(numThreads, maxIter, step, tabuTenure, f);
        System.arraycopy(initial_x, 0, sd.s, 0, N);
        System.arraycopy(lower, 0, sd.lower, 0, N);
        System.arraycopy(upper, 0, sd.upper, 0, N);
        // C does: memcpy(prevBest, best, ...) where best is zeroed
        System.arraycopy(sd.best, 0, sd.prevBest, 0, N);

        Thread[] threads = new Thread[numThreads];
        long start = System.nanoTime();

        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(new Worker(i, sd), "tabu-worker-" + i);
            threads[i].start();
        }
        for (int i = 0; i < numThreads; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        long end = System.nanoTime();
        double time_taken = (end - start) / 1e9;

        System.out.printf("best f = %f %n", sd.fBest);
        System.out.print("best x = [");
        for (int i = 0; i < N; i++) {
            System.out.print(sd.best[i]);
            System.out.print((i == N - 1) ? "" : ", ");
        }
        System.out.println("]");
        System.out.printf("Time elapsed = %.2f seconds%n", time_taken);

        double[] x_star = onesVector(N);
        double resid = norm2_diff(sd.best, x_star, N);
        System.out.printf("residuum ||x - x*||_2 = %.6e%n", resid);
    }

    // === helpers.h ports ===
    static double diffInfNorm(double[] a, double[] b, int n) {
        double m = 0.0;
        for (int i = 0; i < n; i++) {
            double diff = Math.abs(a[i] - b[i]);
            if (diff > m) m = diff;
        }
        return m;
    }

    static double extendedRosenbrock(double[] x, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i += 2) {
            final double x1 = x[i];
            final double x2 = x[i + 1];
            final double term1 = x2 - x1 * x1;
            final double term2 = 1.0 - x1;
            sum += 100.0 * term1 * term1 + term2 * term2;
        }
        return sum;
    }

    static double woodsFunction(double[] x, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i += 4) {
            final double a = x[i];
            final double b = x[i + 1];
            final double c = x[i + 2];
            final double d = x[i + 3];

            final double t1 = b - a * a;
            final double t2 = 1.0 - a;
            final double t3 = d - c * c;
            final double t4 = 1.0 - c;
            final double t5 = b + d - 2.0;
            final double t6 = b - d;

            sum += 100.0 * t1 * t1 + t2 * t2
                    + 90.0  * t3 * t3 + t4 * t4
                    + 10.0  * t5 * t5 + 0.1 * t6 * t6;
        }
        return sum;
    }

    static void rosenbrockX0Th(int dim, double[] x0) {
        for (int i = 0; i < dim; i++) x0[i] = (i % 2 == 0) ? -1.2 : 1.0;
    }

    static void woodsX0Th(int dim, double[] x0) {
        for (int i = 0; i < dim; i++) x0[i] = (i % 2 == 0) ? -3.0 : -1.0;
    }

    static double norm2_diff(double[] x, double[] y, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double d = x[i] - y[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    static double[] onesVector(int n) {
        double[] x = new double[n];
        Arrays.fill(x, 1.0);
        return x;
    }

    // === Main: uses exactly your requested params and 4 threads ===
    public static void main(String[] args) {
        final int threads = 5;              // REQUIRED fastest target
        final int maxIter = 5_000_000;
        final int tabuTenure = 100;
        final double stepRosen = 1e-4;      // 1.0E-4
        final double stepWoods = 2e-4;      // matches your C main

        System.out.println("Running in Threads mode with " + threads + " threads");

        double[] lower = new double[N];
        double[] upper = new double[N];

        // Rosenbrock
        System.out.println("\nRosenbrock start");
        Arrays.fill(lower, -2.0);
        Arrays.fill(upper,  2.0);
        double[] initialRosen = new double[N];
        rosenbrockX0Th(N, initialRosen);
        tabuSearch(threads, tabuTenure, lower, upper, initialRosen,
                MainThreads::extendedRosenbrock, maxIter, stepRosen);

//        // Woods
//        System.out.println("\nWoods start");
//        Arrays.fill(lower, -5.0);
//        Arrays.fill(upper,  5.0);
//        double[] initialWoods = new double[N];
//        woodsX0Th(N, initialWoods);
//        tabuSearch(threads, tabuTenure, lower, upper, initialWoods,
//                MainThreads::woodsFunction, maxIter, stepWoods);
    }
}
