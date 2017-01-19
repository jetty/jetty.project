//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.rhttp.loadtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.mortbay.jetty.rhttp.client.RHTTPClient;
import org.mortbay.jetty.rhttp.client.JettyClient;
import org.mortbay.jetty.rhttp.client.RHTTPListener;
import org.mortbay.jetty.rhttp.client.RHTTPRequest;
import org.mortbay.jetty.rhttp.client.RHTTPResponse;

/**
 * @version $Revision$ $Date$
 */
public class Loader
{
    private final List<RHTTPClient> clients = new ArrayList<RHTTPClient>();
    private final AtomicLong start = new AtomicLong();
    private final AtomicLong end = new AtomicLong();
    private final AtomicLong responses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong minLatency = new AtomicLong();
    private final AtomicLong maxLatency = new AtomicLong();
    private final AtomicLong totLatency = new AtomicLong();
    private final ConcurrentMap<Long, AtomicLong> latencies = new ConcurrentHashMap<Long, AtomicLong>();
    private final String nodeName;

    public static void main(String[] args) throws Exception
    {
        String nodeName = "";
        if (args.length > 0)
            nodeName = args[0];

        Loader loader = new Loader(nodeName);
        loader.run();
    }

    public Loader(String nodeName)
    {
        this.nodeName = nodeName;
    }

    private void run() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setMaxConnectionsPerAddress(40000);
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(500);
        threadPool.setDaemon(true);
        httpClient.setThreadPool(threadPool);
        httpClient.setIdleTimeout(5000);
        httpClient.start();

        Random random = new Random();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        System.err.print("server [localhost]: ");
        String value = console.readLine().trim();
        if (value.length() == 0)
            value = "localhost";
        String host = value;

        System.err.print("port [8080]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "8080";
        int port = Integer.parseInt(value);

        System.err.print("context []: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "";
        String context = value;

        System.err.print("external path [/]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "/";
        String externalPath = value;

        System.err.print("gateway path [/__gateway]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "/__gateway";
        String gatewayPath = value;

        int clients = 100;
        int batchCount = 1000;
        int batchSize = 5;
        long batchPause = 5;
        int requestSize = 50;

        while (true)
        {
            System.err.println("-----");

            System.err.print("clients [" + clients + "]: ");
            value = console.readLine();
            if (value == null)
                break;
            value = value.trim();
            if (value.length() == 0)
                value = "" + clients;
            clients = Integer.parseInt(value);

            System.err.println("Waiting for clients to be ready...");

            Address gatewayAddress = new Address(host, port);
            String gatewayURI = context + gatewayPath;

            // Create or remove the necessary clients
            int currentClients = this.clients.size();
            if (currentClients < clients)
            {
                for (int i = 0; i < clients - currentClients; ++i)
                {
                    final RHTTPClient client = new JettyClient(httpClient, gatewayAddress, gatewayURI, nodeName + (currentClients + i));
                    client.addListener(new EchoListener(client));
                    client.connect();
                    this.clients.add(client);

                    // Give some time to the server to accept connections and
                    // reply to handshakes and connects
                    if (i % 10 == 0)
                    {
                        Thread.sleep(100);
                    }
                }
            }
            else if (currentClients > clients)
            {
                for (int i = 0; i < currentClients - clients; ++i)
                {
                    RHTTPClient client = this.clients.remove(currentClients - i - 1);
                    client.disconnect();
                }
            }

            System.err.println("Clients ready");

            currentClients = this.clients.size();
            if (currentClients > 0)
            {
                System.err.print("batch count [" + batchCount + "]: ");
                value = console.readLine().trim();
                if (value.length() == 0)
                    value = "" + batchCount;
                batchCount = Integer.parseInt(value);

                System.err.print("batch size [" + batchSize + "]: ");
                value = console.readLine().trim();
                if (value.length() == 0)
                    value = "" + batchSize;
                batchSize = Integer.parseInt(value);

                System.err.print("batch pause [" + batchPause + "]: ");
                value = console.readLine().trim();
                if (value.length() == 0)
                    value = "" + batchPause;
                batchPause = Long.parseLong(value);

                System.err.print("request size [" + requestSize + "]: ");
                value = console.readLine().trim();
                if (value.length() == 0)
                    value = "" + requestSize;
                requestSize = Integer.parseInt(value);
                String requestBody = "";
                for (int i = 0; i < requestSize; i++)
                    requestBody += "x";

                String externalURL = "http://" + host + ":" + port + context + externalPath;
                if (!externalURL.endsWith("/"))
                    externalURL += "/";

                reset();

                long start = System.nanoTime();
                long expected = 0;
                for (int i = 0; i < batchCount; ++i)
                {
                    for (int j = 0; j < batchSize; ++j)
                    {
                        int clientIndex = random.nextInt(this.clients.size());
                        RHTTPClient client = this.clients.get(clientIndex);
                        String targetId = client.getTargetId();
                        String url = externalURL + targetId;

                        ExternalExchange exchange = new ExternalExchange();
                        exchange.setMethod("GET");
                        exchange.setURL(url);
                        exchange.setRequestContent(new ByteArrayBuffer(requestBody, "UTF-8"));
                        exchange.send(httpClient);
                        ++expected;
                    }

                    if (batchPause > 0)
                        Thread.sleep(batchPause);
                }
                long end = System.nanoTime();
                long elapsedNanos = end - start;
                if (elapsedNanos > 0)
                {
                    System.err.print("Messages - Elapsed | Rate = ");
                    System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                    System.err.print(" ms | ");
                    System.err.print(expected * 1000 * 1000 * 1000 / elapsedNanos);
                    System.err.println(" requests/s ");
                }

                waitForResponses(expected);
                printReport(expected);
            }
        }
    }

    private void reset()
    {
        start.set(0L);
        end.set(0L);
        responses.set(0L);
        failures.set(0L);
        minLatency.set(Long.MAX_VALUE);
        maxLatency.set(0L);
        totLatency.set(0L);
    }

    private void updateLatencies(long start, long end)
    {
        long latency = end - start;

        // Update the latencies using a non-blocking algorithm
        long oldMinLatency = minLatency.get();
        while (latency < oldMinLatency)
        {
            if (minLatency.compareAndSet(oldMinLatency, latency)) break;
            oldMinLatency = minLatency.get();
        }
        long oldMaxLatency = maxLatency.get();
        while (latency > oldMaxLatency)
        {
            if (maxLatency.compareAndSet(oldMaxLatency, latency)) break;
            oldMaxLatency = maxLatency.get();
        }
        totLatency.addAndGet(latency);

        latencies.putIfAbsent(latency, new AtomicLong(0L));
        latencies.get(latency).incrementAndGet();
    }

    private boolean waitForResponses(long expected) throws InterruptedException
    {
        long arrived = responses.get() + failures.get();
        long lastArrived = 0;
        int maxRetries = 20;
        int retries = maxRetries;
        while (arrived < expected)
        {
            System.err.println("Waiting for responses to arrive " + arrived + "/" + expected);
            Thread.sleep(500);
            if (lastArrived == arrived)
            {
                --retries;
                if (retries == 0) break;
            }
            else
            {
                lastArrived = arrived;
                retries = maxRetries;
            }
            arrived = responses.get() + failures.get();
        }
        if (arrived < expected)
        {
            System.err.println("Interrupting wait for responses " + arrived + "/" + expected);
            return false;
        }
        else
        {
            System.err.println("All responses arrived " + arrived + "/" + expected);
            return true;
        }
    }

    public void printReport(long expectedCount)
    {
        long responseCount = responses.get() + failures.get();
        System.err.print("Messages - Success/Failures/Expected = ");
        System.err.print(responses.get());
        System.err.print("/");
        System.err.print(failures.get());
        System.err.print("/");
        System.err.println(expectedCount);

        long elapsedNanos = end.get() - start.get();
        if (elapsedNanos > 0)
        {
            System.err.print("Messages - Elapsed | Rate = ");
            System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            System.err.print(" ms | ");
            System.err.print(responseCount * 1000 * 1000 * 1000 / elapsedNanos);
            System.err.println(" responses/s ");
        }

        if (latencies.size() > 1)
        {
            long maxLatencyBucketFrequency = 0L;
            long[] latencyBucketFrequencies = new long[20];
            long latencyRange = maxLatency.get() - minLatency.get();
            for (Iterator<Map.Entry<Long, AtomicLong>> entries = latencies.entrySet().iterator(); entries.hasNext();)
            {
                Map.Entry<Long, AtomicLong> entry = entries.next();
                long latency = entry.getKey();
                Long bucketIndex = (latency - minLatency.get()) * latencyBucketFrequencies.length / latencyRange;
                int index = bucketIndex.intValue() == latencyBucketFrequencies.length ? latencyBucketFrequencies.length - 1 : bucketIndex.intValue();
                long value = entry.getValue().get();
                latencyBucketFrequencies[index] += value;
                if (latencyBucketFrequencies[index] > maxLatencyBucketFrequency) maxLatencyBucketFrequency = latencyBucketFrequencies[index];
                entries.remove();
            }

            System.err.println("Messages - Latency Distribution Curve (X axis: Frequency, Y axis: Latency):");
            for (int i = 0; i < latencyBucketFrequencies.length; i++)
            {
                long latencyBucketFrequency = latencyBucketFrequencies[i];
                int value = Math.round(latencyBucketFrequency * (float) latencyBucketFrequencies.length / maxLatencyBucketFrequency);
                if (value == latencyBucketFrequencies.length) value = value - 1;
                for (int j = 0; j < value; ++j) System.err.print(" ");
                System.err.print("@");
                for (int j = value + 1; j < latencyBucketFrequencies.length; ++j) System.err.print(" ");
                System.err.print("  _  ");
                System.err.print(TimeUnit.NANOSECONDS.toMillis((latencyRange * (i + 1) / latencyBucketFrequencies.length) + minLatency.get()));
                System.err.println(" ms (" + latencyBucketFrequency + ")");
            }
        }

        System.err.print("Messages - Latency Min/Ave/Max = ");
        System.err.print(TimeUnit.NANOSECONDS.toMillis(minLatency.get()) + "/");
        System.err.print(responseCount == 0 ? "-/" : TimeUnit.NANOSECONDS.toMillis(totLatency.get() / responseCount) + "/");
        System.err.println(TimeUnit.NANOSECONDS.toMillis(maxLatency.get()) + " ms");
    }

    private class ExternalExchange extends ContentExchange
    {
        private volatile long sendTime;

        private ExternalExchange()
        {
            super(true);
        }

        private void send(HttpClient httpClient) throws IOException
        {
            this.sendTime = System.nanoTime();
            httpClient.send(this);
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            if (getResponseStatus() == 200)
                responses.incrementAndGet();
            else
                failures.incrementAndGet();

            long arrivalTime = System.nanoTime();
            if (start.get() == 0L)
                start.set(arrivalTime);
            end.set(arrivalTime);
            updateLatencies(sendTime, arrivalTime);
        }

        @Override
        protected void onException(Throwable x)
        {
            failures.incrementAndGet();
        }
    }

    private static class EchoListener implements RHTTPListener
    {
        private final RHTTPClient client;

        public EchoListener(RHTTPClient client)
        {
            this.client = client;
        }

        public void onRequest(RHTTPRequest request) throws Exception
        {
            RHTTPResponse response = new RHTTPResponse(request.getId(), 200, "OK", new HashMap<String, String>(), request.getBody());
            client.deliver(response);
        }
    }
}
