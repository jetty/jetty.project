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

package org.eclipse.jetty.util;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 9, time = 800, timeUnit = TimeUnit.MILLISECONDS)
public class PathEncodingBenchmark
{
    private static final String NOENCODING_SHORT = "/path/to/a/resource.txt";
    private static final String NOENCODING_LONG = "/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/foobarbeeerbe/bebbebbebebbe/bebbeghdegde/resource.txt";

    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    private static final String ENCODING_SHORT = "/a (path)/that [needs]/to be {encoded}/euro-€.txt";
    private static final String ENCODING_LONG = "Ọňḉ\uD835\uDC1E ư\uD835\uDC5Dṓŉ ӓ ᵯӏ\uD835\uDDF1\uD835\uDD9Eḯ\uD835\uDD8Cⱨṭ \uD835\uDE25\uD835\uDE9Bḝ\uD835\uDCEAṟƴ, щⱨꙇḻ\uD835\uDC52 Ī \uD835\uDD95\uD835\uDC5Cň\uD835\uDD89ξгéᑯ, ẉêȁ\uD835\uDDC4 ặṋ\uD835\uDE25 щξáṝ\uD835\uDC9A, Ǿṿⅇᵳ ᵯāᵰᶌ \uD835\uDCB6 \uD835\uDC92\uD835\uDF10ąḯղ\uD835\uDC2D ẵńɗ \uD835\uDE24υɍĭօ\uD835\uDE36ṣ \uD835\uDE03ℴŀսᶆḝ ໐ḟ ḟỏᶉɠöⱦ\uD835\uDE9Dξ\uD835\uDED1 ɭоṟⱸ— Ԝḥ\uD835\uDCBEŀẻ Ī \uD835\uDF7F٥ԁ\uD835\uDCB9ӭ\uD835\uDC51, \uD835\uDCC3ẻẫ\uD835\uDE33լ\uD835\uDEFE \uD835\uDF0B\uD835\uDF36\uD835\uDFBA\uD835\uDD2Dîη\uD835\uDC88, şųδ\uD835\uDC51ëꞥĺ\uD835\uDF38 \uD835\uDE35ḧȩ\uD835\uDDFFȇ \uD835\uDDBC⍺мê ä \uD835\uDFBDⱥ\uD835\uDE99\uD835\uDD2Dі\uD835\uDE2Fꞡ, Ѧ\uD835\uDCFC ǭϝ ṧ\uD835\uDE98ṁě õ\uD835\uDF7Fè ꞡẻ\uD835\uDDC7\uD835\uDE35ᶅ\uD835\uDEA2 \uD835\uDD2Fảꝑ\uD835\uDCC5\uD835\uDCBEղ\uD835\uDD58, ꞧ\uD835\uDFAAⲣ\uD835\uDFC8\uD835\uDD5A\uD835\uDF45\uD835\uDC54 \uD835\uDC1A\uD835\uDF0F ɱү ƈ\uD835\uDDF5ȧṃбҿᵲ \uD835\uDE25ôṏᵲ. “’⊤ḭṩ \uD835\uDE68૦ṃ\uD835\uDE5A \uD835\uDF42\uD835\uDECAȿ⍳\uD835\uDD65\uD835\uDD94\uD835\uDCFB,” Ị ṃửτ\uD835\uDCC9ễṙ℮\uD835\uDD89, “\uD835\uDE01\uD835\uDC4Eꝑ\uD835\uDF8Eȉᵰ\uD835\uDDC0 а\uD835\uDD65 м\uD835\uDD02 ɕ\uD835\uDCF1ȃᵯḅḕᴦ ⅆöөꞧ— Ớňᶅ\uD835\uDD36 \uD835\uDED5ḣḯś \uD835\uDC4E\uD835\uDE2Fḑ ℼ\uD835\uDDFC\uD835\uDDCD\uD835\uDE29ȉ\uD835\uDD93\uD835\uDD8C мοṙể.";
    // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void shortNoEncodingURIUtil(Blackhole blackhole)
    {
        blackhole.consume(URIUtil.encodePath(NOENCODING_SHORT));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void shortNoEncodingPathUtilBytes(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePath(NOENCODING_SHORT));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void shortNoEncodingPathUtilDelayedAlloc(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePathDelayAlloc(NOENCODING_SHORT));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void longNoEncodingURIUtil(Blackhole blackhole)
    {
        blackhole.consume(URIUtil.encodePath(NOENCODING_LONG));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void longNoEncodingPathUtilBytes(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePath(NOENCODING_LONG));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void longNoEncodingPathUtilDelayedAlloc(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePathDelayAlloc(NOENCODING_LONG));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void shortEncodingURIUtil(Blackhole blackhole)
    {
        blackhole.consume(URIUtil.encodePath(ENCODING_SHORT));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void shortEncodingPathUtilBytes(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePath(ENCODING_SHORT));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void shortEncodingPathUtilDelayedAlloc(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePathDelayAlloc(ENCODING_SHORT));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void longEncodingURIUtil(Blackhole blackhole)
    {
        blackhole.consume(URIUtil.encodePath(ENCODING_LONG));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void longEncodingPathUtilBytes(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePath(ENCODING_LONG));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    public void longEncodingPathUtilDelayedAlloc(Blackhole blackhole)
    {
        blackhole.consume(PathUtil.encodePathDelayAlloc(ENCODING_LONG));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(PathEncodingBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
