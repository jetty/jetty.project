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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTokens;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.HeaderParser;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.util.NanoTime;
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
 * <p>Benchmarks of the HTTP/2 header path, which is what a h2c connection
 * spends most of its per request time in: frame header parsing, HPACK
 * encoding and decoding, and the pieces they are built from.</p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class HpackBenchmark
{
    private static final int BUFFER_CAPACITY = 8 * 1024;

    private MetaData.Request request;
    private ByteBuffer coldEncodedRequest;
    private ByteBuffer coldEncodedResponse;
    private HpackEncoder warmHpackEncoder;
    private HpackDecoder warmHpackDecoder;
    private ByteBuffer warmEncodedRequest;
    private ByteBuffer warmEncodedResponse;
    private MetaData.Response response;
    private ByteBuffer encodeBuffer;
    private String[] fieldNames;
    private String[] fieldValues;

    @Setup
    public void setup() throws Exception
    {
        HttpFields requestFields = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .put(HttpHeader.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .put(HttpHeader.ACCEPT_ENCODING, "gzip, deflate, br")
            .put(HttpHeader.ACCEPT_LANGUAGE, "en-GB,en;q=0.9,pl;q=0.8")
            .put(HttpHeader.COOKIE, "session=8f14e45fceea167a5a36dedd4bea2543; theme=dark; consent=1")
            .put(HttpHeader.REFERER, "https://example.org/some/previous/page?with=a&query=string")
            .put("X-Request-Id", "b3a1c9e2-4f6d-4a1b-9c3e-7d2f8a5b6c4d")
            .put("X-Forwarded-For", "203.0.113.42, 198.51.100.7");
        request = new MetaData.Request("GET", HttpURI.from("https://example.org/some/resource/path?name=value&other=thing"),
            HttpVersion.HTTP_2, requestFields);

        HttpFields responseFields = HttpFields.build()
            .put(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8")
            .put(HttpHeader.CONTENT_LENGTH, "12345")
            .put(HttpHeader.DATE, "Mon, 27 Jul 2026 12:00:00 GMT")
            .put(HttpHeader.ETAG, "\"5d8c72a5edda8d6a-1f4a\"")
            .put(HttpHeader.CACHE_CONTROL, "max-age=3600, must-revalidate")
            .put(HttpHeader.VARY, "Accept-Encoding, Origin")
            .put("X-Served-By", "cache-lhr7382-LHR");
        response = new MetaData.Response(200, null, HttpVersion.HTTP_2, responseFields);

        encodeBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);

        coldEncodedRequest = encode(request);
        coldEncodedResponse = encode(response);

        // Warm an encoder and decoder pair the way a long lived connection
        // does, so that the dynamic table holds the request fields and they
        // are encoded as indexes rather than literals. The decoder must see
        // exactly the sequence the encoder produced for its table to match.
        warmHpackEncoder = new HpackEncoder();
        warmHpackDecoder = new HpackDecoder(BUFFER_CAPACITY, NanoTime::now);
        for (int i = 0; i < 4; i++)
        {
            warmEncodedRequest = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
            warmHpackEncoder.encode(warmEncodedRequest, request);
            warmEncodedRequest.flip();
            warmHpackDecoder.decode(warmEncodedRequest.slice());

            warmEncodedResponse = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
            warmHpackEncoder.encode(warmEncodedResponse, request);
            warmEncodedResponse.flip();
            warmHpackDecoder.decode(warmEncodedResponse.slice());
        }

        fieldNames = new String[]{"content-type", "x-request-id", "accept-encoding", "cache-control", "x-forwarded-for"};
        fieldValues = new String[]{"text/html; charset=utf-8", "b3a1c9e2-4f6d-4a1b-9c3e-7d2f8a5b6c4d",
            "gzip, deflate, br", "max-age=3600, must-revalidate", "203.0.113.42, 198.51.100.7"};
    }

    private ByteBuffer encode(MetaData metaData) throws Exception
    {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        new HpackEncoder().encode(buffer, metaData);
        buffer.flip();
        return buffer;
    }

    @Benchmark
    public MetaData decodeRequestCold() throws Exception
    {
        return new HpackDecoder(BUFFER_CAPACITY, NanoTime::now).decode(coldEncodedRequest.slice());
    }

    @Benchmark
    public MetaData decodeResponseCold() throws Exception
    {
        return new HpackDecoder(BUFFER_CAPACITY, NanoTime::now).decode(coldEncodedResponse.slice());
    }

    @Benchmark
    public MetaData decodeRequestWarm() throws Exception
    {
        return warmHpackDecoder.decode(warmEncodedRequest.slice());
    }

    @Benchmark
    public MetaData decodeResponseWarm() throws Exception
    {
        return warmHpackDecoder.decode(warmEncodedResponse.slice());
    }

    @Benchmark
    public int encodeRequestCold() throws Exception
    {
        encodeBuffer.clear();
        new HpackEncoder().encode(encodeBuffer, request);
        return encodeBuffer.position();
    }

    @Benchmark
    public int encodeResponseCold() throws Exception
    {
        encodeBuffer.clear();
        new HpackEncoder().encode(encodeBuffer, response);
        return encodeBuffer.position();
    }

    @Benchmark
    public int encodeRequestWarm() throws Exception
    {
        encodeBuffer.clear();
        warmHpackEncoder.encode(encodeBuffer, request);
        return encodeBuffer.position();
    }

    @Benchmark
    public int encodeResponseWarm() throws Exception
    {
        encodeBuffer.clear();
        warmHpackEncoder.encode(encodeBuffer, response);
        return encodeBuffer.position();
    }

    @Benchmark
    public boolean validateFieldNames()
    {
        boolean legal = true;
        for (int i = 0; i < fieldNames.length; i++)
        {
            legal &= HttpTokens.isLegalH2H3FieldName(fieldNames[i]);
        }
        return legal;
    }

    @Benchmark
    public boolean validateFieldValues()
    {
        boolean legal = true;
        for (int i = 0; i < fieldNames.length; i++)
        {
            legal &= HttpTokens.isLegalFieldValue(fieldValues[i]);
        }
        return legal;
    }

    /**
     * Exposes {@link HeaderParser#reset()}, which is protected, so that the
     * benchmark can parse the same frame header over and over.
     */
    private static class BenchmarkHeaderParser extends HeaderParser
    {
        private BenchmarkHeaderParser()
        {
            super(RateControl.NO_RATE_CONTROL);
        }

        @Override
        public void reset()
        {
            super.reset();
        }
    }

    public static void main(String[] args) throws RunnerException
    {
        Options options = new OptionsBuilder()
            .include(HpackBenchmark.class.getSimpleName())
            .forks(1)
            .build();
        new Runner(options).run();
    }
}
