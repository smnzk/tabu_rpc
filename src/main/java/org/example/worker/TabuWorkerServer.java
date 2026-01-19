package org.example.worker;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class TabuWorkerServer {

    public static void main(String[] args) throws Exception {

        // Pass ports 50051, 50052, 50053 etc... to server args[] to run servers

        if (args.length < 1) {
            System.err.println("Provide ports via args to start");
            System.exit(1);
        }

        List<Server> servers = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();

        for (String arg : args) {
            try {
                int port = Integer.parseInt(arg);
                Server server = ServerBuilder
                        .forPort(port)
                        .addService(new TabuWorkerImpl())
                        .build()
                        .start();

                servers.add(server);
                ports.add(port);

                System.out.println("TabuWorker gRPC server started on port " + port);

            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + arg + ", skipping.");
            } catch (IOException e) {
                System.err.println("Failed to start server on port " + arg + ": " + e.getMessage());
            }
        }

        if (servers.isEmpty()) {
            System.err.println("No servers started. Exiting.");
            System.exit(1);
        }

        CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down worker servers on ports " + ports);
            for (Server s : servers) {
                try {
                    s.shutdown();
                } catch (Exception _) {
                }
            }
            latch.countDown();
        }));
        latch.await();
    }
}
