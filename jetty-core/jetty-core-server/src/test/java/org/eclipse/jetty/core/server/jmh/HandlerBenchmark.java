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

package org.eclipse.jetty.core.server.jmh;

import java.nio.ByteBuffer;

import org.eclipse.jetty.core.server.ConnectionMetaData;
import org.eclipse.jetty.core.server.HttpChannel;
import org.eclipse.jetty.core.server.HttpConfiguration;
import org.eclipse.jetty.core.server.MockConnectionMetaData;
import org.eclipse.jetty.core.server.MockHttpStream;
import org.eclipse.jetty.core.server.Server;
import org.eclipse.jetty.core.server.handler.ContextHandler;
import org.eclipse.jetty.core.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.core.server.handler.DelayedHandler;
import org.eclipse.jetty.core.server.handler.EchoHandler;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.BufferUtil;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@State(Scope.Benchmark)
public class HandlerBenchmark
{
    static Server _server = new Server();
    static HttpConfiguration _httpConfig = new HttpConfiguration();

    static String message = "ECHO Echo echo";
    static ByteBuffer body = BufferUtil.toBuffer(message);
    static HttpFields fields = HttpFields.build()
        .add(HttpHeader.HOST, "localhost")
        .putLongField(HttpHeader.CONTENT_LENGTH, body.remaining())
        .add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_8859_1.asString())
        .asImmutable();
    static HttpURI uri = HttpURI.from("http://localhost/ctx/path");

    @Setup(Level.Trial)
    public static void setupServer() throws Exception
    {
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
    public long testPost()
    {
        ConnectionMetaData connectionMetaData = new MockConnectionMetaData();
        HttpChannel channel = new HttpChannel(_server, connectionMetaData, _httpConfig);
        MockHttpStream stream = new MockHttpStream(channel, false);

        stream.addContent(body.slice(), true);
        MetaData.Request request = new MetaData.Request("POST", uri, HttpVersion.HTTP_1_1, fields, message.length());
        Runnable todo = channel.onRequest(request);
        todo.run();

        assertThat(stream.isComplete(), is(true));
        assertThat(stream.getFailure(), nullValue());
        assertThat(stream.getResponse(), notNullValue());
        assertThat(stream.getResponse().getStatus(), equalTo(200));
        assertThat(stream.getResponse().getFields().get(HttpHeader.CONTENT_TYPE), equalTo(MimeTypes.Type.TEXT_PLAIN_8859_1.asString()));
        assertThat(BufferUtil.toString(stream.getResponseContent()), equalTo(message));
        return stream.getResponseContentAsString().hashCode();
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
