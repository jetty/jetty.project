//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.jmh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpChannelListeners;
import org.eclipse.jetty.server.HttpChannelListenersList;
import org.eclipse.jetty.server.HttpChannelListenersMethodHandles;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import static org.eclipse.jetty.server.jmh.ListVsMapBenchmark.trials;

@State(Scope.Benchmark)
@Threads(1)
@Warmup(iterations = 6, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
public class ListenersBenchmark
{
    @Param({"0", "1", "5"})
    public static int listeners;

    @Param({"existing", "list", "lambda", "methodhandle"})
    public static String type;

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong count = new AtomicLong();

    List<HttpChannel.Listener> list;
    HttpChannel.Listener listener;

    @Setup(Level.Trial)
    public void setup()
    {
        requests.set(0);
        count.set(0);

        list = new ArrayList<>();
        if (listeners >= 1)
        {
            list.add(new MyListener0());

            if (listeners >= 2)
            {
                list.add(new MyListener1());

                for (int i = 3; i <= listeners; i++)
                {
                    final int random = ThreadLocalRandom.current().nextInt(100);

                    list.add(new MyListener2(random));
                }
            }
        }
        switch (type)
        {
            case "existing":
                listener = null;
                break;
            case "list":
                listener = new HttpChannelListenersList(Collections.unmodifiableList(list));
                break;
            case "lambda":
                listener = new HttpChannelListeners(list);
                break;
            case "methodhandle":
                listener = new HttpChannelListenersMethodHandles(list);
                break;
            default:
                throw new IllegalStateException();
        }

        Collections.shuffle(trials);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testListeners() throws Exception
    {
        Request request = new Request(null, null);
        HttpChannel.Listener l = listener;
        if (l == null)
            l = new HttpChannelListenersList(Collections.unmodifiableList(list));
        l.onRequestBegin(request);
        l.onBeforeDispatch(request);
        l.onRequestContent(request, BufferUtil.toBuffer("Request Content"));
        l.onRequestContentEnd(request);
        l.onResponseEnd(request);
        l.onResponseBegin(request);
        l.onResponseCommit(request);
        l.onResponseContent(request, BufferUtil.toBuffer("Response Content"));
        l.onResponseContent(request, BufferUtil.toBuffer("Chunk0"));
        l.onResponseContent(request, BufferUtil.toBuffer("Chunk1"));
        l.onResponseContent(request, BufferUtil.toBuffer("Chunk2"));
        l.onResponseContent(request, BufferUtil.toBuffer("Chunk3"));
        l.onResponseEnd(request);
        l.onComplete(request);
        return count.get() + requests.get();
    }

    public class MyListener0 implements HttpChannel.Listener
    {
        @Override
        public void onRequestBegin(Request request)
        {
            request.setAttribute("value", this);
            requests.incrementAndGet();
        }

        @Override
        public void onComplete(Request request)
        {
            requests.decrementAndGet();
            Object o = request.getAttribute("value");
            request.removeAttribute("value");
            if (o!=null && o.hashCode()>0)
                count.addAndGet(o.hashCode());
        }
    }

    public class MyListener1 implements HttpChannel.Listener
    {
        @Override
        public void onRequestBegin(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onBeforeDispatch(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onDispatchFailure(Request request, Throwable failure)
        {
            count.incrementAndGet();
        }

        @Override
        public void onAfterDispatch(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onRequestContent(Request request, ByteBuffer content)
        {
            count.addAndGet(content.remaining());
        }

        @Override
        public void onRequestContentEnd(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onRequestTrailers(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onRequestEnd(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onResponseBegin(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onResponseCommit(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onResponseContent(Request request, ByteBuffer content)
        {
            count.addAndGet(content.remaining());
        }

        @Override
        public void onResponseEnd(Request request)
        {
            count.incrementAndGet();
        }

        @Override
        public void onComplete(Request request)
        {
            count.incrementAndGet();
        }
    }

    public class MyListener2 implements HttpChannel.Listener
    {
        private final int random;

        public MyListener2(int random)
        {
            this.random = random;
        }

        @Override
        public void onRequestBegin(Request request)
        {
            count.addAndGet(random);
        }

        @Override
        public void onComplete(Request request)
        {
            count.addAndGet(-random);
        }
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ListenersBenchmark.class.getSimpleName())
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .warmupTime(new TimeValue(5, TimeUnit.SECONDS))
            .measurementTime(new TimeValue(5, TimeUnit.SECONDS))
            .build();

        new Runner(opt).run();
    }
}


