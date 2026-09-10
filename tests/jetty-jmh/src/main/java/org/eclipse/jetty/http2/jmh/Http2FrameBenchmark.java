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

package org.eclipse.jetty.http2.jmh;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.HeadersGenerator;
import org.eclipse.jetty.http2.generator.SettingsGenerator;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.HeaderParser;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.parser.PrefaceParser;
import org.eclipse.jetty.http2.parser.SettingsBodyParser;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * <p>Benchmarks of the HTTP/2 frame path, one layer above
 * HpackBenchmark: generating and parsing whole frames, so that the
 * share of a frame that HPACK actually accounts for can be seen.</p>
 * <p>Both a warmed encoder and decoder pair and a cold one are covered, as the
 * dynamic table changes the balance completely.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class Http2FrameBenchmark
{
    private static final int STREAM_ID = 13;

    private final ByteBufferPool bufferPool = new ArrayByteBufferPool();
    private MetaData.Request request;
    private HeaderParser warmHeaderParser;
    private ByteBuffer warmHeadersFrame;
    private ByteBuffer coldHeadersFrame;
    private ByteBuffer prefaceFrame;
    private PrefaceParser prefaceParser;
    private ByteBuffer warmSettingsFrame;
    private SettingsBodyParser warmSettingsBodyParser;
    private ByteBuffer coldSettingsFrame;

    @Setup
    public void setup() throws Exception
    {
        HttpFields fields = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .put(HttpHeader.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .put(HttpHeader.ACCEPT_ENCODING, "gzip, deflate, br")
            .put(HttpHeader.ACCEPT_LANGUAGE, "en-GB,en;q=0.9,pl;q=0.8")
            .put(HttpHeader.COOKIE, "session=8f14e45fceea167a5a36dedd4bea2543; theme=dark; consent=1")
            .put("X-Request-Id", "b3a1c9e2-4f6d-4a1b-9c3e-7d2f8a5b6c4d");
        request = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"),
            "/some/resource/path?name=value", HttpVersion.HTTP_2, fields, -1);

        HeadersGenerator warmHeadersGenerator = new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder());

        warmHeaderParser = new HeaderParser(RateControl.NO_RATE_CONTROL);

        // Warm one generator and parser pair the way a connection warms them.
        for (int i = 0; i < 4; i++)
        {
            warmHeadersFrame = generateHeaders(warmHeadersGenerator);
            warmHeaderParser.parse(warmHeadersFrame.slice());
        }

        coldHeadersFrame = generateHeaders(new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder()));

        prefaceFrame = ByteBuffer.wrap(PrefaceFrame.PREFACE_BYTES);
        prefaceParser = new PrefaceParser(new Parser.Listener() {});

        SettingsGenerator warmSettingsGenerator = new SettingsGenerator(new HeaderGenerator(bufferPool));

        warmSettingsFrame = generateSettings(warmSettingsGenerator);
        warmSettingsBodyParser = new SettingsBodyParser(warmHeaderParser, new Parser.Listener() {});

        coldSettingsFrame = generateSettings(new SettingsGenerator(new HeaderGenerator(bufferPool)));
    }

    private ByteBuffer generateSettings(SettingsGenerator generator)
    {
        RetainableByteBuffer.Mutable buffer = new RetainableByteBuffer.DynamicCapacity();
        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, 32768);
        settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, 128);
        settings.put(SettingsFrame.MAX_FRAME_SIZE, 8192);
        settings.put(SettingsFrame.HEADER_TABLE_SIZE, 4094);
        generator.generateSettings(buffer, settings, true);
        return toByteBuffer(buffer);
    }

    private ByteBuffer generateHeaders(HeadersGenerator generator) throws Exception
    {
        RetainableByteBuffer.Mutable buffer = new RetainableByteBuffer.DynamicCapacity();
        generator.generateHeaders(buffer, STREAM_ID, request, null, true);
        return toByteBuffer(buffer);
    }

    private static ByteBuffer toByteBuffer(RetainableByteBuffer.Mutable buffer)
    {
        ByteBuffer slice = buffer.getByteBuffer();
        ByteBuffer copy = ByteBuffer.allocateDirect(slice.remaining());
        copy.put(slice).flip();
        buffer.release();
        return copy;
    }

    @Benchmark
    public boolean parseHeadersFrameWarm()
    {
        return warmHeaderParser.parse(warmHeadersFrame.slice());
    }

    @Benchmark
    public boolean parseHeadersFrameCold()
    {
        // A fresh parser each time, so the dynamic table is empty and every
        // field arrives as a literal, as on the first message of a connection.
        return new HeaderParser(RateControl.NO_RATE_CONTROL).parse(coldHeadersFrame.slice());
    }

    @Benchmark
    public boolean parseSettingsFrameWarm()
    {
        return warmSettingsBodyParser.parse(warmSettingsFrame.slice());
    }

    @Benchmark
    public boolean parseSettingsFrameCold()
    {
        return new SettingsBodyParser(new HeaderParser(RateControl.NO_RATE_CONTROL), new Parser.Listener() {})
            .parse(coldSettingsFrame.slice());
    }

    @Benchmark
    public boolean parsePreface()
    {
        return prefaceParser.parse(prefaceFrame.slice());
    }


    public static void main(String[] args) throws RunnerException
    {
        Options options = new OptionsBuilder()
            .include(Http2FrameBenchmark.class.getSimpleName())
            .forks(1)
            .build();
        new Runner(options).run();
    }
}
