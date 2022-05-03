//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DelayedHandler;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.util.StringUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@State(Scope.Benchmark)
public class HandlerBenchmark
{
    static Server _server = new Server();
    static ServerConnector _connector = new ServerConnector(_server);

    static final byte[] GET = """
                    GET /ctx/path HTTP/1.1\r
                    Host: localhost\r
                    X-Forwarded-For: 192.168.0.1\r
                    \r
                    """.getBytes(StandardCharsets.ISO_8859_1);

    static final byte[] POST = """
                    POST /ctx/path HTTP/1.1\r
                    Host: localhost\r
                    Content-Length: 16\r
                    Content-Type: text/plain; charset=iso-8859-1\r
                    X-Forwarded-For: 192.168.0.1\r
                    \r
                    ECHO Echo echo\r
                    """.getBytes(StandardCharsets.ISO_8859_1);

    static final byte[] POST_CLOSE = """
                    POST /ctx/path HTTP/1.1\r
                    Host: localhost\r
                    Content-Length: 16\r
                    Content-Type: text/plain; charset=iso-8859-1\r
                    X-Forwarded-For: 192.168.0.1\r
                    Connection: close\r
                    \r
                    ECHO Echo echo\r
                    """.getBytes(StandardCharsets.ISO_8859_1);

    @Setup(Level.Trial)
    public static void setupServer() throws Exception
    {
        _server.addConnector(_connector);
        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());
        DelayedHandler.UntilContent delayedHandler = new DelayedHandler.UntilContent();
        _server.setHandler(delayedHandler);
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        delayedHandler.setHandler(contexts);
        ContextHandler context = new ContextHandler("/ctx");
        contexts.addHandler(context);
        EchoHandler echo = new EchoHandler();
        context.setHandler(echo);
        _server.start();
    }

    @TearDown(Level.Trial)
    public static void stopServer() throws Exception
    {
        _server.stop();
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public long testPost() throws Exception
    {
        try (Socket client = new Socket("127.0.0.1", _connector.getLocalPort()))
        {
            OutputStream out = client.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

            long result = 0;

            // Do some GETs
            for (int i = 5; i-- > 0;)
            {
                out.write(GET);
                out.flush();

                String line = in.readLine();
                assertThat(line, containsString(" 200 OK"));
                while (StringUtil.isNotBlank(line))
                {
                    line = in.readLine();
                    result ^= line.hashCode();
                }
            }

            // Do some POSTs
            for (int i = 5; i-- > 0;)
            {
                out.write(i == 0 ? POST_CLOSE : POST);
                out.flush();

                String line = in.readLine();
                assertThat(line, containsString(" 200 OK"));
                while (StringUtil.isNotBlank(line))
                {
                    line = in.readLine();
                    result ^= line.hashCode();
                }
                line = in.readLine();
                assertThat(line, containsString("ECHO Echo echo"));
            }
            return result;
        }
    }

    public static void main(String[] args) throws Exception
    {
        Options opt = new OptionsBuilder()
            .include(HandlerBenchmark.class.getSimpleName())
            .warmupIterations(10)
            .warmupTime(TimeValue.seconds(4))
            .measurementIterations(10)
            .measurementTime(TimeValue.seconds(4))
            // .addProfiler(GCProfiler.class)
            // .addProfiler(LinuxPerfAsmProfiler.class)
            .forks(1)
            .threads(10)
            .build();

        new Runner(opt).run();
    }
}
