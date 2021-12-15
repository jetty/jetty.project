//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ManagedSelector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerConnectorAcceptTest
{
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    public void testAccept(int acceptors) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, acceptors, 1);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response) throws Exception
            {
                request.succeeded();
                return true;
            }
        });
        server.start();

        try
        {
            test(acceptors, connector);
        }
        finally
        {
            server.stop();
        }
    }

    private void test(int acceptors, ServerConnector connector) throws InterruptedException
    {
        // TODO this test is way too slow
        int threads = 8;
        int iterations = 256;

        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        CountDownLatch latch = new CountDownLatch(threads * iterations);
        IntStream.range(0, threads)
            .mapToObj(t -> new Thread(() ->
            {
                try
                {
                    assertTrue(awaitBarrier(barrier));

                    long start = System.nanoTime();
                    for (int i = 0; i < iterations; ++i)
                    {
                        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
                        {
                            String request = "GET / HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Connection: close\r\n" +
                                "\r\n";
                            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                            HttpTester.Response response = HttpTester.parseResponse(socket.getInputStream());
                            assertNotNull(response);
                            assertEquals(HttpStatus.OK_200, response.getStatus());
                        }
                        catch (IOException x)
                        {
                            x.printStackTrace();
                        }
                        finally
                        {
                            latch.countDown();
                        }
                    }
                    long elapsed = System.nanoTime() - start;
                    System.err.printf("%d acceptors, %d threads, %d requests each, time = %d ms%n",
                        acceptors,
                        threads,
                        iterations,
                        TimeUnit.NANOSECONDS.toMillis(elapsed));
                }
                finally
                {
                    assertTrue(awaitBarrier(barrier));
                }
            }))
            .forEach(Thread::start);

        // Wait for all the threads to be ready.
        assertTrue(awaitBarrier(barrier));

        // Wait for all the threads to be finished.
        assertTrue(awaitBarrier(barrier));

        // Verify that all requests succeeded.
        assertTrue(latch.await(15, TimeUnit.SECONDS));

        Collection<ManagedSelector> selectors = connector.getSelectorManager().getBeans(ManagedSelector.class);
        selectors.stream()
            .map(s -> String.format("avg selected keys = %.3f, selects = %d", s.getAverageSelectedKeys(), s.getSelectCount()))
            .forEach(System.err::println);
        selectors.forEach(ManagedSelector::resetStats);
    }

    private boolean awaitBarrier(CyclicBarrier barrier)
    {
        try
        {
            barrier.await();
            return true;
        }
        catch (Throwable x)
        {
            return false;
        }
    }
}
