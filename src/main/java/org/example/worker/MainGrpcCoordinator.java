package org.example.worker;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.example.tabu.proto.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public final class MainGrpcCoordinator {

    static final int N = 200;
    static final double EPSILON = 1e-10;

    // ===== Helpers (unchanged) =====
    static double diffInfNorm(double[] a, double[] b, int n) {
        double m = 0.0;
        for (int i = 0; i < n; i++) {
            double diff = Math.abs(a[i] - b[i]);
            if (diff > m) m = diff;
        }
        return m;
    }

    static void rosenbrockX0Th(int dim, double[] x0) {
        for (int i = 0; i < dim; i++) x0[i] = (i % 2 == 0) ? -1.2 : 1.0;
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

    // Same chunking formula
    static int[] chunkForRank(int rank, int T) {
        int base = N / T;
        int rem  = N % T;
        int chunkStart = rank * base + (rank < rem ? rank : rem);
        int chunkSize  = base + (rank < rem ? 1 : 0);
        int chunkEnd   = chunkStart + chunkSize;
        return new int[]{chunkStart, chunkEnd};
    }

    // ============================================================
    // Worker stream client wrapper (new proto)
    // ============================================================
    static final class WorkerClient {
        final int rank;
        final int chunkStart;
        final int chunkEnd;

        final ManagedChannel channel;
        final StreamObserver<WorkerMessage> requestStream;

        // responses (Ack or IterationResponse) come back asynchronously
        final BlockingQueue<WorkerResponse> responses = new LinkedBlockingQueue<>();

        WorkerClient(int rank, String host, int port, int chunkStart, int chunkEnd) {
            this.rank = rank;
            this.chunkStart = chunkStart;
            this.chunkEnd = chunkEnd;

            this.channel = ManagedChannelBuilder.forAddress(host, port)
                    .directExecutor()
                    .usePlaintext()
                    .build();

            TabuWorkerGrpc.TabuWorkerStub stub = TabuWorkerGrpc.newStub(channel);

            this.requestStream = stub.streamEvaluate(new StreamObserver<>() {
                @Override
                public void onNext(WorkerResponse value) {
                    responses.offer(value);
                }

                @Override
                public void onError(Throwable t) {
                    System.err.println("Worker " + rank + " stream error: " + t);
                }

                @Override
                public void onCompleted() {
                    System.err.println("Worker " + rank + " completed stream.");
                }
            });
        }

        void sendInit(double[] solution0, double[] lower, double[] upper, int tabuTenure) {
            Init.Builder ib = Init.newBuilder()
                    .setRank(rank)
                    .setN(solution0.length)
                    .setChunkStart(chunkStart)
                    .setChunkEnd(chunkEnd)
                    .setTabuTenure(tabuTenure);

            for (double v : solution0) ib.addSolution0(v);
            for (double v : lower) ib.addLower(v);
            for (double v : upper) ib.addUpper(v);

            WorkerMessage msg = WorkerMessage.newBuilder()
                    .setInit(ib.build())
                    .build();

            requestStream.onNext(msg);
        }

        void sendIteration(int iteration,
                           double step,
                           int chosenIndex,
                           int chosenDirection,
                           double chosenNewValue,
                           double fBest,
                           int maxCandidates) {

            Iteration iter = Iteration.newBuilder()
                    .setIteration(iteration)
                    .setStep(step)
                    .setChosenIndex(chosenIndex)
                    .setChosenDirection(chosenDirection)
                    .setChosenNewValue(chosenNewValue)
                    .setFBest(fBest)
                    .setMaxCandidates(maxCandidates)
                    .build();

            requestStream.onNext(
                    WorkerMessage.newBuilder().setIter(iter).build()
            );
        }

        void sendResync(int iteration, double[] solution, double fBest) {
            Resync.Builder rb = Resync.newBuilder()
                    .setIteration(iteration)
                    .setFBest(fBest);

            for (double v : solution) rb.addSolution(v);

            requestStream.onNext(
                    WorkerMessage.newBuilder().setResync(rb.build()).build()
            );
        }

        // Wait until we receive IterationResponse for expected iteration.
        // (Acks can arrive too; we ignore them here.)
        IterationResponse awaitIterResponse(int expectedIteration, long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

            while (true) {
                long remainingNs = deadline - System.nanoTime();
                if (remainingNs <= 0) {
                    throw new RuntimeException("Timeout waiting for worker " + rank
                            + " iteration " + expectedIteration);
                }
                WorkerResponse r;
                try {
                    r = responses.poll(remainingNs, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for worker " + rank, e);
                }
                if (r == null) continue;

                if (r.hasAck()) {
                    // optional: print once
                    // System.out.println("Worker " + rank + " ACK: " + r.getAck().getMsg());
                    continue;
                }
                if (!r.hasIterResp()) continue;

                IterationResponse ir = r.getIterResp();
                if (ir.getIteration() != expectedIteration) {
                    throw new RuntimeException("Worker " + rank
                            + " iteration mismatch. Expected " + expectedIteration
                            + " got " + ir.getIteration());
                }
                return ir;
            }
        }

        void close() {
            try { requestStream.onCompleted(); } catch (Exception ignored) {}
            channel.shutdownNow();
        }
    }

    // ============================================================
    // Coordinator-side tabu search using new protocol
    // ============================================================
    static void tabuSearchGrpc(
            List<WorkerClient> workers,
            int tabuTenure,
            double[] lower,
            double[] upper,
            double[] initial_x,
            int maxIter,
            double step,
            int maxCandidatesPerWorker
    ) {
        System.out.println("==========================");
        System.out.println("Dimension = " + N);
        System.out.println("Max iter = " + maxIter);
        System.out.println("Step = " + step);
        System.out.println("Tab tenure = " + tabuTenure);
        System.out.println("Workers = " + workers.size());
        System.out.println("==========================");

        // Dedicated executor for blocking I/O (better than ForkJoinPool for this use case)
        ExecutorService responseExecutor = Executors.newFixedThreadPool(workers.size());

        // Shared state
        double[] s = new double[N];
        double[] best = new double[N];
        double[] prevBest = new double[N];

        int[] tabuExpireMinus = new int[N];
        int[] tabuExpirePlus  = new int[N];

        System.arraycopy(initial_x, 0, s, 0, N);
        // best[] defaults to 0; keep same behavior
        System.arraycopy(best, 0, prevBest, 0, N);

        double fBest = Double.POSITIVE_INFINITY;
        double fs = Double.POSITIVE_INFINITY;

        int stagnation = 0;
        final int patience = 8000;  // increased for N=500

        // ---- INIT streams (send once) ----
        for (WorkerClient w : workers) {
            w.sendInit(s, lower, upper, tabuTenure);
        }

        // previous chosen move (to broadcast next iter)
        int prevChosenIndex = -1;
        int prevChosenDir = 0;
        double prevChosenNewVal = 0.0;

        // Optional safety resync (off by default)
        final int RESYNC_EVERY = 0; // e.g. 50_000; 0 disables

        long start = System.nanoTime();

        for (int k = 1; k <= maxIter; k++) {

            // ---- optionally resync full state sometimes ----
            if (RESYNC_EVERY > 0 && k % RESYNC_EVERY == 0) {
                for (WorkerClient w : workers) {
                    w.sendResync(k, s, fBest);
                }
                // after resync, we still send Iteration as normal
            }

//// ---- Adaptive step near optimum ----
            double effectiveStep = step;
            if (fBest < 10) {
                effectiveStep = step * 0.1;
            }

            // ---- Fork: send Iteration to all workers (tiny) ----
            for (WorkerClient w : workers) {
                w.sendIteration(
                        k,
                        effectiveStep,
                        prevChosenIndex,
                        prevChosenDir,
                        prevChosenNewVal,
                        fBest,
                        maxCandidatesPerWorker
                );
            }

            // ---- Join: collect worker responses IN PARALLEL ----
            final int iteration = k;
            List<CompletableFuture<IterationResponse>> futures = new ArrayList<>(workers.size());
            for (WorkerClient w : workers) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> w.awaitIterResponse(iteration, 60_000),
                        responseExecutor
                ));
            }

            List<IterationResponse> responses;
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                responses = new ArrayList<>(workers.size());
                for (CompletableFuture<IterationResponse> f : futures) {
                    responses.add(f.get());
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to collect worker responses", e);
            }

            // ---- Reduce: choose best move globally ----
            // Workers already filtered tabu moves (except aspiration), so just find min
            CandidateMove bestMove = null;
            double bestF = Double.POSITIVE_INFINITY;

            for (IterationResponse resp : responses) {
                for (CandidateMove mv : resp.getCandidatesList()) {
                    double fc = mv.getFValue();
                    if (fc < bestF) {
                        bestF = fc;
                        bestMove = mv;
                    }
                }
            }

            // ---- Apply: update global state ----
            if (bestMove != null) {
                int moveVar = bestMove.getIndex();
                int dir = bestMove.getDirection();

                s[moveVar] = bestMove.getNewValue();
                fs = bestMove.getFValue();

                if (fs < fBest) {
                    fBest = fs;
                    System.arraycopy(s, 0, best, 0, N);
                }

                // Tabu update EXACTLY like your original coordinator code
                if (dir == -1) {
                    tabuExpirePlus[moveVar] = k + tabuTenure;
                } else {
                    tabuExpireMinus[moveVar] = k + tabuTenure;
                }

                // remember chosen move to broadcast next iter
                prevChosenIndex = moveVar;
                prevChosenDir = dir;
                prevChosenNewVal = bestMove.getNewValue();
            } else {
                // no admissible move found; still must send something next iteration
                prevChosenIndex = -1;
                prevChosenDir = 0;
                prevChosenNewVal = 0.0;
            }

            // ---- Stagnation (unchanged) ----
            double d = diffInfNorm(best, prevBest, N);
            if (d < EPSILON) stagnation++;
            else {
                stagnation = 0;
                System.arraycopy(best, 0, prevBest, 0, N);
            }

            if (stagnation >= patience) {
                System.out.println("Stagnation criterion met at iteration " + k);
                break;
            }
        }

        long end = System.nanoTime();
        double time_taken = (end - start) / 1e9;

        System.out.printf("best f = %f %n", fBest);
        System.out.print("best x = [");
        for (int i = 0; i < N; i++) {
            System.out.print(best[i]);
            System.out.print((i == N - 1) ? "" : ", ");
        }
        System.out.println("]");
        System.out.printf("Time elapsed = %.2f seconds%n", time_taken);

        double[] x_star = onesVector(N);
        double resid = norm2_diff(best, x_star, N);
        System.out.printf("residuum ||x - x*||_2 = %.6e%n", resid);

        // Shutdown executor
        responseExecutor.shutdown();
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {

        final int numWorkers = 1;          // must match number of worker servers
        final int maxIter = 2_000_000;     // more iterations for larger N
        final int tabuTenure = 13;         // ~sqrt(500), classic heuristic
        final double stepRosen = 1e-3;     // larger step for faster initial convergence

        // With the new design, you can set this SMALL (1..3).
        // 3 gives better exploration for larger problems.
        final int maxCandidatesPerWorker = 3;

        System.out.println("Running in gRPC worker mode with " + numWorkers + " workers");

        double[] lower = new double[N];
        double[] upper = new double[N];

        System.out.println("\nRosenbrock start");
        Arrays.fill(lower, -2.0);
        Arrays.fill(upper,  2.0);

        double[] initialRosen = new double[N];
        rosenbrockX0Th(N, initialRosen);

        List<WorkerClient> workers = new ArrayList<>();
        for (int rank = 0; rank < numWorkers; rank++) {
            int[] ch = chunkForRank(rank, numWorkers);
            int port = 50051 + rank;
            workers.add(new WorkerClient(rank, "localhost", port, ch[0], ch[1]));
        }

        try {
            tabuSearchGrpc(
                    workers,
                    tabuTenure,
                    lower,
                    upper,
                    initialRosen,
                    maxIter,
                    stepRosen,
                    maxCandidatesPerWorker
            );
        } finally {
            for (WorkerClient w : workers) w.close();
        }
    }
}
