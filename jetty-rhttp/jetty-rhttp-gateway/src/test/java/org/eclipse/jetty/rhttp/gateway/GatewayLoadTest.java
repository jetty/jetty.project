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

package org.eclipse.jetty.rhttp.gateway;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.ByteArrayBuffer;

/**
 * @version $Revision$ $Date$
 */
public class GatewayLoadTest extends TestCase
{
    private final ConcurrentMap<Long, AtomicLong> latencies = new ConcurrentHashMap<Long, AtomicLong>();
    private final AtomicLong responses = new AtomicLong(0L);
    private final AtomicLong failures = new AtomicLong(0L);
    private final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLatency = new AtomicLong(0L);
    private final AtomicLong totLatency = new AtomicLong(0L);

    public void testEcho() throws Exception
    {
        GatewayEchoServer server = new GatewayEchoServer();
        server.start();

        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.start();

        String uri = server.getURI() + "/";

        char[] chars = new char[1024];
        Arrays.fill(chars, 'x');
        String requestBody = new String(chars);

        int count = 1000;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            GatewayLoadTestExchange exchange = new GatewayLoadTestExchange(latch);
            exchange.setMethod(HttpMethods.POST);
            exchange.setAddress(server.getAddress());
            exchange.setURI(uri + i);
            exchange.setRequestContent(new ByteArrayBuffer(requestBody.getBytes("UTF-8")));
            exchange.setStartNanos(System.nanoTime());
            httpClient.send(exchange);
            Thread.sleep(5);
        }
        latch.await();
        printLatencies(count);
        assertEquals(count, responses.get() + failures.get());
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

    public void printLatencies(long expectedCount)
    {
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

            System.out.println("Messages - Latency Distribution Curve (X axis: Frequency, Y axis: Latency):");
            for (int i = 0; i < latencyBucketFrequencies.length; i++)
            {
                long latencyBucketFrequency = latencyBucketFrequencies[i];
                int value = Math.round(latencyBucketFrequency * (float) latencyBucketFrequencies.length / maxLatencyBucketFrequency);
                if (value == latencyBucketFrequencies.length) value = value - 1;
                for (int j = 0; j < value; ++j) System.out.print(" ");
                System.out.print("@");
                for (int j = value + 1; j < latencyBucketFrequencies.length; ++j) System.out.print(" ");
                System.out.print("  _  ");
                System.out.print(TimeUnit.NANOSECONDS.toMillis((latencyRange * (i + 1) / latencyBucketFrequencies.length) + minLatency.get()));
                System.out.print(" (" + latencyBucketFrequency + ")");
                System.out.println(" ms");
            }
        }

        long responseCount = responses.get();
        System.out.print("Messages success/failed/expected = ");
        System.out.print(responseCount);
        System.out.print("/");
        System.out.print(failures.get());
        System.out.print("/");
        System.out.print(expectedCount);
        System.out.print(" - Latency min/ave/max = ");
        System.out.print(TimeUnit.NANOSECONDS.toMillis(minLatency.get()) + "/");
        System.out.print(responseCount == 0 ? "-/" : TimeUnit.NANOSECONDS.toMillis(totLatency.get() / responseCount) + "/");
        System.out.println(TimeUnit.NANOSECONDS.toMillis(maxLatency.get()) + " ms");
    }

    private class GatewayLoadTestExchange extends ContentExchange
    {
        private final CountDownLatch latch;
        private volatile long start;

        private GatewayLoadTestExchange(CountDownLatch latch)
        {
            super(true);
            this.latch = latch;
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            if (getResponseStatus() == HttpServletResponse.SC_OK)
            {
                long end = System.nanoTime();
                responses.incrementAndGet();
                updateLatencies(start, end);
            }
            else
            {
                failures.incrementAndGet();
            }
            latch.countDown();
        }

        @Override
        protected void onException(Throwable throwable)
        {
            failures.incrementAndGet();
            latch.countDown();
        }

        @Override
        protected void onExpire()
        {
            failures.incrementAndGet();
            latch.countDown();
        }

        public void setStartNanos(long value)
        {
            start = value;
        }
    }
}
