package org.example.worker;

import io.grpc.stub.StreamObserver;
import org.example.tabu.proto.*;


public final class TabuWorkerImpl extends TabuWorkerGrpc.TabuWorkerImplBase {

    @Override
    public StreamObserver<WorkerMessage> streamEvaluate(
            StreamObserver<WorkerResponse> responseObserver) {

        return new StreamObserver<>() {

            // ===== persistent state =====
            int rank;
            int N;
            int chunkStart, chunkEnd;
            int tabuTenure;

            double[] s;
            double[] lower;
            double[] upper;

            int[] tabuExpireMinus;
            int[] tabuExpirePlus;

            boolean initialized = false;

            // top-K buffers
            int[] topIdx;
            int[] topDir;
            double[] topNewVal;
            double[] topF;

            // ======================================================

            @Override
            public void onNext(WorkerMessage msg) {

                if (msg.hasInit()) {
                    handleInit(msg.getInit());
                    return;
                }

                if (!initialized) {
                    throw new IllegalStateException("Iteration before Init");
                }

                if (msg.hasResync()) {
                    handleResync(msg.getResync());
                    return;
                }

                if (msg.hasIter()) {
                    handleIteration(msg.getIter());
                }
            }

            // ================= INIT =================
            private void handleInit(Init init) {

                this.rank = init.getRank();
                this.N = init.getN();
                this.chunkStart = init.getChunkStart();
                this.chunkEnd = init.getChunkEnd();
                this.tabuTenure = init.getTabuTenure();

                s = new double[N];
                lower = new double[N];
                upper = new double[N];

                for (int i = 0; i < N; i++) {
                    s[i] = init.getSolution0(i);
                    lower[i] = init.getLower(i);
                    upper[i] = init.getUpper(i);
                }

                tabuExpireMinus = new int[N];
                tabuExpirePlus = new int[N];

                initialized = true;

                responseObserver.onNext(
                        WorkerResponse.newBuilder()
                                .setAck(Ack.newBuilder()
                                        .setMsg("Worker " + rank + " initialized")
                                        .build())
                                .build()
                );
            }

            // ================= RESYNC =================
            private void handleResync(Resync r) {
                for (int i = 0; i < N; i++) {
                    s[i] = r.getSolution(i);
                }
            }

            // ================= ITERATION =================
            private void handleIteration(Iteration it) {

                final int k = it.getIteration();
                final double step = it.getStep();
                final double fBest = it.getFBest();
                final int K = Math.max(1, it.getMaxCandidates());

                ensureTopCapacity(K);

                // ---- apply coordinator move ----
                if (k > 1) {
                    int idx = it.getChosenIndex();
                    if (idx >= 0) {
                        int dir = it.getChosenDirection();
                        double newVal = it.getChosenNewValue();

                        s[idx] = newVal;

                        if (dir == -1) {
                            tabuExpirePlus[idx] = k + tabuTenure;
                        } else {
                            tabuExpireMinus[idx] = k + tabuTenure;
                        }
                    }
                }

                int size = 0;
                int worstPos = -1;
                double worstF = Double.NEGATIVE_INFINITY;

                // ---- evaluate my chunk ----
                for (int i = chunkStart; i < chunkEnd; i++) {

                    double oldVal = s[i];

                    for (int d = 0; d < 2; d++) {

                        int dir = (d == 0) ? -1 : 1;

                        double newVal = oldVal + dir * step;
                        if (newVal < lower[i]) newVal = lower[i];
                        if (newVal > upper[i]) newVal = upper[i];

                        boolean isTabu = (dir == -1)
                                ? (k < tabuExpireMinus[i])
                                : (k < tabuExpirePlus[i]);

                        s[i] = newVal;
                        double fc = ObjectiveFunctions.evaluate(s);
                        s[i] = oldVal;

                        boolean aspiration = fc < fBest;

                        if (isTabu && !aspiration) continue;

                        if (size < K) {
                            topIdx[size] = i;
                            topDir[size] = dir;
                            topNewVal[size] = newVal;
                            topF[size] = fc;
                            size++;

                            if (worstPos == -1 || fc > worstF) {
                                worstF = fc;
                                worstPos = size - 1;
                            }
                        } else if (fc < worstF) {

                            topIdx[worstPos] = i;
                            topDir[worstPos] = dir;
                            topNewVal[worstPos] = newVal;
                            topF[worstPos] = fc;

                            worstPos = 0;
                            worstF = topF[0];
                            for (int t = 1; t < K; t++) {
                                if (topF[t] > worstF) {
                                    worstF = topF[t];
                                    worstPos = t;
                                }
                            }
                        }
                    }
                }

                sortTop(size);

                IterationResponse.Builder rb =
                        IterationResponse.newBuilder().setIteration(k);

                for (int i = 0; i < size; i++) {
                    rb.addCandidates(
                            CandidateMove.newBuilder()
                                    .setIndex(topIdx[i])
                                    .setDirection(topDir[i])
                                    .setNewValue(topNewVal[i])
                                    .setFValue(topF[i])
                                    .build()
                    );
                }

                responseObserver.onNext(
                        WorkerResponse.newBuilder()
                                .setIterResp(rb.build())
                                .build()
                );
            }

            // ================= HELPERS =================

            private void ensureTopCapacity(int k) {
                if (topF == null || topF.length < k) {
                    topIdx = new int[k];
                    topDir = new int[k];
                    topNewVal = new double[k];
                    topF = new double[k];
                }
            }

            private void sortTop(int n) {
                for (int i = 0; i < n - 1; i++) {
                    int best = i;
                    double bestF = topF[i];
                    for (int j = i + 1; j < n; j++) {
                        if (topF[j] < bestF) {
                            bestF = topF[j];
                            best = j;
                        }
                    }
                    if (best != i) swap(i, best);
                }
            }

            private void swap(int i, int j) {
                int ti = topIdx[i]; topIdx[i] = topIdx[j]; topIdx[j] = ti;
                int td = topDir[i]; topDir[i] = topDir[j]; topDir[j] = td;
                double tn = topNewVal[i]; topNewVal[i] = topNewVal[j]; topNewVal[j] = tn;
                double tf = topF[i]; topF[i] = topF[j]; topF[j] = tf;
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Worker stream error: " + t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }
}


final class ObjectiveFunctions {

    static double evaluate(double[] x) {
        return extendedRosenbrock(x);
        // or woodsFunction(x)
    }

    static double extendedRosenbrock(double[] x) {
        double sum = 0.0;
        for (int i = 0; i < x.length; i += 2) {
            double x1 = x[i];
            double x2 = x[i + 1];
            double t1 = x2 - x1 * x1;
            double t2 = 1.0 - x1;
            sum += 100.0 * t1 * t1 + t2 * t2;
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

}
