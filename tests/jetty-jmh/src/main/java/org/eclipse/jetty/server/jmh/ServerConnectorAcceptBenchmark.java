//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.jmh;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@State(Scope.Benchmark)
public class ServerConnectorAcceptBenchmark
{
    public static void main(String[] args) throws Exception
    {
        Options opt = new OptionsBuilder()
            .include(ServerConnectorAcceptBenchmark.class.getSimpleName())
            .warmupIterations(10)
            .warmupTime(TimeValue.milliseconds(500))
            .measurementIterations(10)
            .measurementTime(TimeValue.milliseconds(500))
            .forks(1)
            .threads(20)
            .build();
        new Runner(opt).run();
    }

//    @Param({"0", "1", "2", "4"})
    @Param({"4", "2", "1", "0"})
    public int acceptors;

    final LongAdder count = new LongAdder();

    Server server;
    ServerConnector connector;

    @Setup
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, acceptors, -1);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
                return true;
            }
        });
        server.start();
    }

    @TearDown
    public void dispose() throws Exception
    {
        System.err.println("count = " + count.sum());
        server.stop();
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public void accept() throws Exception
    {
        count.increment();
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            channel.write(StandardCharsets.US_ASCII.encode("GET / HTTP/1.0\r\n\r\n"));
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(channel));
            if (response.getStatus() != HttpStatus.OK_200)
                System.err.println("x = " + response);
        }
        catch (Throwable x)
        {
            System.err.println("x = " + x);
        }
    }
}
