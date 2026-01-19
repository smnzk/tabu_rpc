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

    static void woodsX0(int dim, double[] x0) {
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

    static int[] chunkForRank(int rank, int T) {
        int base = N / T;
        int rem  = N % T;
        int chunkStart = rank * base + (rank < rem ? rank : rem);
        int chunkSize  = base + (rank < rem ? 1 : 0);
        int chunkEnd   = chunkStart + chunkSize;
        return new int[]{chunkStart, chunkEnd};
    }

    static final class WorkerClient {
        final int rank;
        final int chunkStart;
        final int chunkEnd;

        final ManagedChannel channel;
        final StreamObserver<WorkerMessage> requestStream;

        final BlockingQueue<WorkerResponse> responses = new LinkedBlockingQueue<>();

        WorkerClient(int rank, String host, int port, int chunkStart, int chunkEnd) {
            this.rank = rank;
            this.chunkStart = chunkStart;
            this.chunkEnd = chunkEnd;

            this.channel = ManagedChannelBuilder.forAddress(host, port)
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

        void sendInit(double[] solution0, double[] lower, double[] upper, int tabuTenure, ObjectiveFunction objective) {
            Init.Builder ib = Init.newBuilder()
                    .setRank(rank)
                    .setN(solution0.length)
                    .setChunkStart(chunkStart)
                    .setChunkEnd(chunkEnd)
                    .setTabuTenure(tabuTenure)
                    .setObjective(objective);

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

    static void tabuSearchGrpc(
            List<WorkerClient> workers,
            int tabuTenure,
            double[] lower,
            double[] upper,
            double[] initial_x,
            int maxIter,
            double step,
            int maxCandidatesPerWorker,
            ObjectiveFunction objective
    ) {
        System.out.println("==========================");
        System.out.println("Dimension = " + N);
        System.out.println("Max iter = " + maxIter);
        System.out.println("Step = " + step);
        System.out.println("Tab tenure = " + tabuTenure);
        System.out.println("Workers = " + workers.size());
        System.out.println("Objective = " + objective);
        System.out.println("==========================");

        ExecutorService responseExecutor = Executors.newFixedThreadPool(workers.size());

        double[] s = new double[N];
        double[] best = new double[N];
        double[] prevBest = new double[N];

        System.arraycopy(initial_x, 0, s, 0, N);
        System.arraycopy(best, 0, prevBest, 0, N);

        double fBest = Double.POSITIVE_INFINITY;
        double fs = Double.POSITIVE_INFINITY;

        int stagnation = 0;
        final int patience = 8000;

        for (WorkerClient w : workers) {
            w.sendInit(s, lower, upper, tabuTenure, objective);
        }

        int prevChosenIndex = -1;
        int prevChosenDir = 0;
        double prevChosenNewVal = 0.0;

        long start = System.nanoTime();

        for (int k = 1; k <= maxIter; k++) {

            double effectiveStep = step;
            if (fBest < 10) {
                effectiveStep = step * 0.1;
            }

            final double stepToSend = effectiveStep;
            final int prevIdx = prevChosenIndex;
            final int prevDir = prevChosenDir;
            final double prevNewVal = prevChosenNewVal;
            final double fBestToSend = fBest;
            final int iter = k;

            CountDownLatch sendLatch = new CountDownLatch(workers.size());
            for (WorkerClient w : workers) {
                responseExecutor.execute(() -> {
                    try {
                        w.sendIteration(
                                iter,
                                stepToSend,
                                prevIdx,
                                prevDir,
                                prevNewVal,
                                fBestToSend,
                                maxCandidatesPerWorker
                        );
                    } finally {
                        sendLatch.countDown();
                    }
                });
            }
            try {
                sendLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during send", e);
            }

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

            if (bestMove != null) {
                int moveVar = bestMove.getIndex();
                int dir = bestMove.getDirection();

                s[moveVar] = bestMove.getNewValue();
                fs = bestMove.getFValue();

                if (fs < fBest) {
                    fBest = fs;
                    System.arraycopy(s, 0, best, 0, N);
                }

                prevChosenIndex = moveVar;
                prevChosenDir = dir;
                prevChosenNewVal = bestMove.getNewValue();
            } else {
                prevChosenIndex = -1;
                prevChosenDir = 0;
                prevChosenNewVal = 0.0;
            }

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

        responseExecutor.shutdown();
    }

    public static void main(String[] args) {

        final ObjectiveFunction USE_OBJECTIVE = ObjectiveFunction.ROSENBROCK;

        final int numWorkers = 4;
        final int maxIter = 2_000_000;
        final int tabuTenure = 12;

        final int maxCandidatesPerWorker = 3;

        System.out.println("Running in gRPC worker mode with " + numWorkers + " workers");

        double[] lower = new double[N];
        double[] upper = new double[N];
        double[] initialX = new double[N];
        double step;

        if (USE_OBJECTIVE == ObjectiveFunction.ROSENBROCK) {
            System.out.println("\nRosenbrock start");
            Arrays.fill(lower, -2.0);
            Arrays.fill(upper,  2.0);
            rosenbrockX0Th(N, initialX);
            step = 1e-3;
        } else {
            System.out.println("\nWoods start");
            Arrays.fill(lower, -10.0);
            Arrays.fill(upper,  10.0);
            woodsX0(N, initialX);
            step = 1e-3;
        }

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
                    initialX,
                    maxIter,
                    step,
                    maxCandidatesPerWorker,
                    USE_OBJECTIVE
            );
        } finally {
            for (WorkerClient w : workers) w.close();
        }
    }
}
