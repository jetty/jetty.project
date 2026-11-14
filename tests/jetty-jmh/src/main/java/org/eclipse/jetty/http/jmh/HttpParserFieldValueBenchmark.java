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

package org.eclipse.jetty.http.jmh;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersion;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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

/**
 * <p>Measures the time taken by {@link HttpParser} to parse a typical HTTP/1.1 request,
 * which is dominated by the parsing of the field values.</p>
 * <p>With a {@code headerCacheSize} of 0 every field value is parsed from the buffer, as
 * for the first request of a connection. Otherwise the fields that the parser caches
 * (for example {@code User-Agent} and {@code Cookie}) are looked up rather than parsed,
 * as for the subsequent requests of a persistent connection.</p>
 */
@State(Scope.Benchmark)
@Threads(1)
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HttpParserFieldValueBenchmark
{
    private static final String REQUEST = """
        GET /api/v1/orders/12345?expand=items&locale=ko-KR HTTP/1.1\r
        Host: api.example.com\r
        User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36\r
        Accept: application/json, text/plain, */*\r
        Accept-Language: ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7\r
        Accept-Encoding: gzip, deflate, br, zstd\r
        Referer: https://www.example.com/orders/12345\r
        Origin: https://www.example.com\r
        Cookie: JSESSIONID=node01x8f3k2m9q7v5w1b4c6d8e0g2h.node0; _ga=GA1.2.1234567890.1700000000; theme=dark\r
        X-Request-Id: 7f3c2a9e-5b1d-4c8a-9e6f-2d4b8a1c3e5f\r
        X-Forwarded-For: 203.0.113.42, 198.51.100.17\r
        X-Forwarded-Proto: https\r
        Sec-Fetch-Site: same-site\r
        Sec-Fetch-Mode: cors\r
        Sec-Fetch-Dest: empty\r
        If-None-Match: W/"5e1f-18c2a9b7d40"\r
        Connection: keep-alive\r
        \r
        """;

    @Param({"heap", "direct"})
    String buffer;

    @Param({"0", "1024"})
    int headerCacheSize;

    private ByteBuffer request;
    private Handler handler;
    private HttpParser parser;

    @Setup
    public void setup()
    {
        byte[] bytes = REQUEST.getBytes(StandardCharsets.ISO_8859_1);
        request = "direct".equals(buffer) ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.allocate(bytes.length);
        request.put(bytes).flip();
        handler = new Handler();
        parser = new HttpParser(handler);
        parser.setHeaderCacheSize(headerCacheSize);
    }

    @Benchmark
    public int testParseRequest()
    {
        ByteBuffer buffer = request;
        buffer.position(0);
        parser.reset();
        handler.consumed = 0;
        while (!parser.isState(HttpParser.State.END))
        {
            if (!parser.parseNext(buffer) && !buffer.hasRemaining())
                throw new IllegalStateException(handler.failure);
        }
        return handler.consumed;
    }

    private static class Handler implements HttpParser.RequestHandler
    {
        private int consumed;
        private String failure;

        @Override
        public void startRequest(String method, String uri, HttpVersion version)
        {
            consumed += method.length() + uri.length();
        }

        @Override
        public void parsedHeader(HttpField field)
        {
            consumed += field.getName().length() + field.getValue().length();
        }

        @Override
        public boolean headerComplete()
        {
            return false;
        }

        @Override
        public boolean content(ByteBuffer item)
        {
            return false;
        }

        @Override
        public boolean contentComplete()
        {
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            return true;
        }

        @Override
        public void earlyEOF()
        {
            failure = "early EOF";
        }

        @Override
        public void badMessage(HttpException failure)
        {
            this.failure = failure.getReason();
        }
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(HttpParserFieldValueBenchmark.class.getSimpleName())
            // .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
