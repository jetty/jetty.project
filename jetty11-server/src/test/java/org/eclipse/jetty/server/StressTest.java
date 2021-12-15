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
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("stress")
public class StressTest
{
    private static final Logger LOG = LoggerFactory.getLogger(StressTest.class);

    private static QueuedThreadPool _threads;
    private static Server _server;
    private static ServerConnector _connector;
    private static final AtomicInteger _handled = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue[] _latencies = {
        new ConcurrentLinkedQueue<Long>(),
        new ConcurrentLinkedQueue<Long>(),
        new ConcurrentLinkedQueue<Long>(),
        new ConcurrentLinkedQueue<Long>(),
        new ConcurrentLinkedQueue<Long>(),
        new ConcurrentLinkedQueue<Long>()
    };

    private volatile AtomicInteger[] _loops;
    private final Random _random = new Random();
    private static final String[] __tests =
        {
            "/path/0",
            "/path/1",
            "/path/2",
            "/path/3",
            "/path/4",
            "/path/5",
            "/path/6",
            "/path/7",
            "/path/8",
            "/path/9",
            "/path/a",
            "/path/b",
            "/path/c",
            "/path/d",
            "/path/e",
            "/path/f",
        };

    @BeforeAll
    public static void init() throws Exception
    {
        _threads = new QueuedThreadPool();
        _threads.setMaxThreads(200);

        _server = new Server(_threads);
        _server.manage(_threads);
        _connector = new ServerConnector(_server, null, null, null, 1, 1, new HttpConnectionFactory());
        _connector.setAcceptQueueSize(5000);
        _connector.setIdleTimeout(30000);
        _server.addConnector(_connector);

        TestHandler handler = new TestHandler();
        _server.setHandler(handler);

        _server.start();
    }

    @AfterAll
    public static void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @BeforeEach
    public void reset()
    {
        _handled.set(0);
        for (Queue q : _latencies)
        {
            q.clear();
        }
    }

    @Test
    public void testMinNonPersistent() throws Throwable
    {
        doThreads(10, 10, false);
    }

    @Test
    @Tag("Slow")
    public void testNonPersistent() throws Throwable
    {
        doThreads(20, 20, false);
        Thread.sleep(1000);
        doThreads(200, 10, false);
        Thread.sleep(1000);
        doThreads(200, 200, false);
    }

    @Test
    public void testMinPersistent() throws Throwable
    {
        doThreads(10, 10, true);
    }

    @Test
    @Tag("Slow")
    public void testPersistent() throws Throwable
    {
        doThreads(40, 40, true);
        Thread.sleep(1000);
        doThreads(200, 10, true);
        Thread.sleep(1000);
        doThreads(200, 200, true);
    }

    private void doThreads(int threadCount, final int loops, final boolean persistent) throws Throwable
    {
        final Throwable[] throwables = new Throwable[threadCount];
        final Thread[] threads = new Thread[threadCount];

        try
        {
            for (int i = 0; i < threadCount; i++)
            {
                final int id = i;
                final String name = "T" + i;
                Thread.sleep(_random.nextInt(100));
                threads[i] = new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            doLoops(id, name, loops, persistent);
                        }
                        catch (Throwable th)
                        {
                            th.printStackTrace();
                            throwables[id] = th;
                        }
                    }
                };
            }

            _loops = new AtomicInteger[threadCount];
            for (int i = 0; i < threadCount; i++)
            {
                _loops[i] = new AtomicInteger(0);
                threads[i].start();
            }

            String last = null;
            int same = 0;

            while (true)
            {
                Thread.sleep(1000L);
                int finished = 0;
                int errors = 0;
                int min = loops;
                int max = 0;
                int total = 0;
                for (int i = 0; i < threadCount; i++)
                {
                    int l = _loops[i].get();
                    if (l < 0)
                    {
                        errors++;
                        total -= l;
                    }
                    else
                    {
                        if (l < min)
                            min = l;
                        if (l > max)
                            max = l;
                        total += l;
                        if (l == loops)
                            finished++;
                    }
                }
                String status = "min/ave/max/target=" + min + "/" + (total / threadCount) + "/" + max + "/" + loops + " errors/finished/loops=" + errors + "/" + finished + "/" + threadCount + " idle/threads=" + (_threads.getIdleThreads()) + "/" + _threads.getThreads();
                if (status.equals(last))
                {
                    if (same++ > 5)
                    {
                        System.err.println("STALLED!!!");
                        System.err.println(_server.getThreadPool().toString());
                        Thread.sleep(5000);
                        System.exit(1);
                    }
                }
                else
                    same = 0;
                last = status;
                LOG.info(_server.getThreadPool().toString() + " " + status);
                if ((finished + errors) == threadCount)
                    break;
            }

            for (Thread thread : threads)
            {
                thread.join();
            }

            for (Throwable throwable : throwables)
            {
                if (throwable != null)
                    throw throwable;
            }

            for (ConcurrentLinkedQueue latency : _latencies)
            {
                assertEquals(_handled.get(), latency.size());
            }
        }
        finally
        {
            // System.err.println();
            final int quantums = 48;
            final int[][] count = new int[_latencies.length][quantums];
            final int[] length = new int[_latencies.length];
            final int[] other = new int[_latencies.length];

            long total = 0;

            for (int i = 0; i < _latencies.length; i++)
            {
                Queue<Long> latencies = _latencies[i];
                length[i] = latencies.size();

                loop:
                for (long latency : latencies)
                {
                    if (i == 4)
                        total += latency;
                    for (int q = 0; q < quantums; q++)
                    {
                        if (latency >= (q * 100) && latency < ((q + 1) * 100))
                        {
                            count[i][q]++;
                            continue loop;
                        }
                    }
                    other[i]++;
                }
            }

            System.out.println("           stage:\tbind\twrite\trecv\tdispatch\twrote\ttotal");
            for (int q = 0; q < quantums; q++)
            {
                System.out.printf("%02d00<=l<%02d00", q, (q + 1));
                for (int i = 0; i < _latencies.length; i++)
                {
                    System.out.print("\t" + count[i][q]);
                }
                System.out.println();
            }

            System.out.print("other       ");
            for (int i = 0; i < _latencies.length; i++)
            {
                System.out.print("\t" + other[i]);
            }
            System.out.println();

            System.out.print("HANDLED     ");
            for (int i = 0; i < _latencies.length; i++)
            {
                System.out.print("\t" + _handled.get());
            }
            System.out.println();
            System.out.print("TOTAL       ");
            for (int i = 0; i < _latencies.length; i++)
            {
                System.out.print("\t" + length[i]);
            }
            System.out.println();
            long ave = total / _latencies[4].size();
            System.out.println("ave=" + ave);
        }
    }

    private void doLoops(int thread, String name, int loops, boolean persistent) throws Exception
    {
        try
        {
            for (int i = 0; i < loops; i++)
            {
                _loops[thread].set(i);
                doPaths(thread, name + "-" + i, persistent);
                Thread.sleep(1 + _random.nextInt(20) * _random.nextInt(20));
                Thread.sleep(20);
            }
            _loops[thread].set(loops);
        }
        catch (Exception e)
        {
            System.err.println(e);
            _loops[thread].set(-_loops[thread].get());
            throw e;
        }
    }

    private void doPaths(int thread, String name, boolean persistent) throws Exception
    {
        if (persistent)
        {
            long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            Socket socket = new Socket("localhost", _connector.getLocalPort());
            socket.setSoTimeout(30000);

            long connected = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

            for (int i = 0; i < __tests.length; i++)
            {
                String uri = __tests[i] + "/" + name + "/" + i;

                String close = ((i + 1) < __tests.length) ? "" : "Connection: close\r\n";
                String request =
                    "GET " + uri + " HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "start: " + start + "\r\n" +
                        close + "\r\n";

                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();
                Thread.yield();
            }

            long written = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

            String response = IO.toString(socket.getInputStream());
            socket.close();

            long end = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

            int bodies = count(response, "HTTP/1.1 200 OK");
            if (__tests.length != bodies)
                System.err.println("responses=\n" + response + "\n---");
            assertEquals(__tests.length, bodies, name);

            long bind = connected - start;
            long flush = (written - connected) / __tests.length;
            long read = (end - written) / __tests.length;

            int offset = 0;
            for (int i = 0; i < __tests.length; i++)
            {
                offset = response.indexOf("DATA " + __tests[i], offset);
                assertTrue(offset >= 0);
                offset += __tests[i].length() + 5;

                if (bind < 0 || flush < 0 || read < 0)
                {
                    System.err.println(bind + "," + flush + "," + read);
                }

                _latencies[0].add((i == 0) ? bind : 0L);
                _latencies[1].add((i == 0) ? (bind + flush) : flush);
                _latencies[5].add((i == 0) ? (bind + flush + read) : (flush + read));
            }
        }
        else
        {
            for (int i = 0; i < __tests.length; i++)
            {
                String uri = __tests[i] + "/" + name + "/" + i;

                long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                String close = "Connection: close\r\n";
                String request =
                    "GET " + uri + " HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "start: " + start + "\r\n" +
                        close + "\r\n";

                Socket socket = new Socket("localhost", _connector.getLocalPort());
                socket.setSoTimeout(10000);

                _latencies[0].add((TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start));

                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();

                _latencies[1].add((TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start));

                String response = IO.toString(socket.getInputStream());
                socket.close();
                long end = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

                String endOfResponse = "\r\n\r\n";
                assertThat(response, containsString(endOfResponse));
                response = response.substring(response.indexOf(endOfResponse) + endOfResponse.length());

                assertThat(uri, response, startsWith("DATA " + __tests[i]));
                long latency = end - start;

                _latencies[5].add(latency);
            }
        }
    }

    private int count(String s, String sub)
    {
        int count = 0;
        int index = s.indexOf(sub);

        while (index >= 0)
        {
            count++;
            index = s.indexOf(sub, index + sub.length());
        }
        return count;
    }

    private static class TestHandler extends HandlerWrapper
    {
        @Override
        public void handle(String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response) throws IOException, ServletException
        {
            long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            long start = Long.parseLong(baseRequest.getHeader("start"));
            long received = baseRequest.getTimeStamp();

            _handled.incrementAndGet();
            long delay = received - start;
            if (delay < 0)
                delay = 0;
            _latencies[2].add(delay);
            _latencies[3].add((now - start));

            response.setStatus(200);
            response.getOutputStream().print("DATA " + request.getPathInfo() + "\n\n");
            baseRequest.setHandled(true);

            _latencies[4].add((TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start));

            return;
        }
    }
}
