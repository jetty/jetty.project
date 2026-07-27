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
import org.eclipse.jetty.http.compression.HuffmanDecoder;
import org.eclipse.jetty.http.compression.HuffmanEncoder;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.HeaderParser;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.parser.PrefaceParser;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
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
    private MetaData.Response response;
    private ByteBuffer encodeBuffer;
    private ByteBuffer encodedRequest;
    private ByteBuffer encodedResponse;
    private ByteBuffer frameHeader;
    private ByteBuffer preface;
    private PrefaceParser prefaceParser;
    private BenchmarkHeaderParser headerParser;
    private HuffmanDecoder huffmanDecoder;
    private ByteBuffer huffmanEncoded;
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

        encodeBuffer = ByteBuffer.allocate(BUFFER_CAPACITY);
        encodedRequest = encode(request);
        encodedResponse = encode(response);

        // A DATA frame header: length 16384, type 0, flags 0, stream 5.
        frameHeader = ByteBuffer.wrap(new byte[]{0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05});
        headerParser = new BenchmarkHeaderParser();

        preface = ByteBuffer.wrap(PrefaceFrame.PREFACE_BYTES);
        prefaceParser = new PrefaceParser(new Parser.Listener() {});

        String value = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        huffmanEncoded = ByteBuffer.allocate(HuffmanEncoder.octetsNeeded(value));
        HuffmanEncoder.encode(huffmanEncoded, value);
        huffmanEncoded.flip();
        huffmanDecoder = new HuffmanDecoder();

        fieldNames = new String[]{"content-type", "x-request-id", "accept-encoding", "cache-control", "x-forwarded-for"};
        fieldValues = new String[]{"text/html; charset=utf-8", "b3a1c9e2-4f6d-4a1b-9c3e-7d2f8a5b6c4d",
            "gzip, deflate, br", "max-age=3600, must-revalidate", "203.0.113.42, 198.51.100.7"};
    }

    private ByteBuffer encode(MetaData metaData) throws Exception
    {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_CAPACITY);
        new HpackEncoder().encode(buffer, metaData);
        buffer.flip();
        return buffer;
    }

    @Benchmark
    public int encodeRequest() throws Exception
    {
        encodeBuffer.clear();
        new HpackEncoder().encode(encodeBuffer, request);
        return encodeBuffer.position();
    }

    @Benchmark
    public int encodeResponse() throws Exception
    {
        encodeBuffer.clear();
        new HpackEncoder().encode(encodeBuffer, response);
        return encodeBuffer.position();
    }

    @Benchmark
    public MetaData decodeRequest() throws Exception
    {
        return new HpackDecoder(BUFFER_CAPACITY, NanoTime::now).decode(encodedRequest.slice());
    }

    @Benchmark
    public MetaData decodeResponse() throws Exception
    {
        return new HpackDecoder(BUFFER_CAPACITY, NanoTime::now).decode(encodedResponse.slice());
    }

    @Benchmark
    public int parseFrameHeader()
    {
        frameHeader.position(0);
        headerParser.reset();
        headerParser.parse(frameHeader);
        return headerParser.getStreamId();
    }

    @Benchmark
    public boolean parsePreface()
    {
        preface.position(0);
        return prefaceParser.parse(preface);
    }

    @Benchmark
    public String decodeHuffman() throws Exception
    {
        ByteBuffer buffer = huffmanEncoded.slice();
        huffmanDecoder.reset();
        huffmanDecoder.setLength(buffer.remaining());
        return huffmanDecoder.decode(buffer);
    }

    @Benchmark
    public int encodeHuffman()
    {
        encodeBuffer.clear();
        for (String value : fieldValues)
        {
            HuffmanEncoder.encode(encodeBuffer, value);
        }
        return encodeBuffer.position();
    }

    @Benchmark
    public boolean validateFields()
    {
        boolean legal = true;
        for (int i = 0; i < fieldNames.length; i++)
        {
            legal &= HttpTokens.isLegalH2H3FieldName(fieldNames[i]);
            legal &= HttpTokens.isLegalFieldValue(fieldValues[i]);
        }
        return legal;
    }

    @Benchmark
    public int toLowerCase()
    {
        int length = 0;
        for (String name : fieldNames)
        {
            length += StringUtil.asciiToLowerCase(name).length();
        }
        return length;
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
