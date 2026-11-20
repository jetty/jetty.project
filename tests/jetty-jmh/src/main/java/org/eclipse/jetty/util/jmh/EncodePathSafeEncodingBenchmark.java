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

package org.eclipse.jetty.util.jmh;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Compares the original {@code char}-stepping implementation of
 * {@code URIUtil.encodePathSafeEncoding(String)} against the current
 * codepoint-iterating implementation.
 *
 * <p>
 * Both implementations are copied in here verbatim and share a single copy of the
 * {@code encodeCodepoint}/{@code mustBeEncoded} helpers (which are identical for both),
 * so the only difference the benchmark measures is the iteration strategy: a per-{@code char}
 * {@code for} loop versus a per-codepoint {@code while} loop. Keeping both here also means the
 * comparison survives after the original implementation is gone from the production source.
 * </p>
 */
@Fork(value = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
public class EncodePathSafeEncodingBenchmark
{
    /**
     * The shape of the input path, each exercising a different mix of code paths:
     * <ul>
     *     <li>{@code ascii} - all-safe, no builder is ever allocated (the fast return path).</li>
     *     <li>{@code bmp} - raw BMP characters that must be UTF-8 encoded.</li>
     *     <li>{@code emoji} - a supplementary (non-BMP) codepoint; the case the fix is about.</li>
     *     <li>{@code preencoded} - already percent-encoded octets to be normalized/kept.</li>
     *     <li>{@code mixed} - raw emoji + a pre-encoded {@code %2f} + a raw BMP character.</li>
     *     <li>{@code long} - a longer, mostly-ASCII path with a couple of emoji segments.</li>
     * </ul>
     */
    @Param({"ascii", "bmp", "emoji", "preencoded", "mixed", "long"})
    String variant;

    String input;

    @Setup(Level.Trial)
    public void setupTrial()
    {
        input = switch (variant)
        {
            case "ascii" -> "/context/path/to/some/resource/index.html";
            case "bmp" -> "/bãm/€uro/café/index.html";
            case "emoji" -> "/😀/index.html";
            case "preencoded" -> "/foo/%2F/b%C3%A3m/%E2%82%AC/index.html";
            case "mixed" -> "/😀%2f€/foo.txt";
            case "long" -> "/very/long/context/path/with/many/safe/segments/" +
                "😀/and/a/few/more/😃/segments/to/walk/index.html";
            default -> throw new IllegalArgumentException("Unknown variant: " + variant);
        };
    }

    @Benchmark
    public void original(Blackhole blackhole)
    {
        blackhole.consume(encodePathSafeEncodingOriginal(input));
    }

    @Benchmark
    public void codepoint(Blackhole blackhole)
    {
        blackhole.consume(URIUtil.encodePathSafeEncoding(input));
    }

    // ------------------------------------------------------------------
    // Original implementation: per-char for loop (pre-fix).
    // ------------------------------------------------------------------
    private static String encodePathSafeEncodingOriginal(String path)
    {
        if (path == null)
            return null;
        if (path.isEmpty() || "/".equals(path))
            return path;

        int offset = 0;
        int length = path.length();

        try
        {
            Utf8StringBuilder builder = null;
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                char c = path.charAt(i);
                if (c == '%')
                {
                    if (builder == null)
                    {
                        builder = new Utf8StringBuilder(path.length());
                        builder.append(path, offset, i - offset);
                    }
                    if ((i + 2) < end)
                    {
                        char u = path.charAt(i + 1);
                        if (u == 'u')
                        {
                            if (TypeUtil.isHex(path, i + 2, 4))
                            {
                                int codepoint = (0xffff & TypeUtil.parseInt(path, i + 2, 4, 16));
                                encodeCodepoint(codepoint, builder);
                                i += 5;
                            }
                            else
                            {
                                builder.append("%25");
                            }
                        }
                        else
                        {
                            if (TypeUtil.isHex(path, i + 1, 2))
                            {
                                byte b = (byte)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2))));
                                if (mustBeEncoded(b) || b == 0x2F)
                                    encodeCodepoint(b, builder);
                                else
                                    builder.append(b);
                                i += 2;
                            }
                            else
                            {
                                builder.append("%25");
                            }
                        }
                    }
                    else
                    {
                        builder.append("%25");
                    }
                }
                else
                {
                    if (mustBeEncoded(c))
                    {
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }
                        encodeCodepoint(c, builder);
                    }
                    else
                    {
                        if (builder != null)
                            builder.append(c);
                    }
                }
            }

            if (builder != null)
                return builder.toCompleteString();
            return path;
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("cannot decode URI", e);
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers (identical for both implementations), copied from URIUtil.
    // ------------------------------------------------------------------
    private static void encodeCodepoint(int code, Utf8StringBuilder builder)
    {
        if (code <= 0x7F)
        {
            builder.append('%');
            appendHexValue(builder, (byte)code);
        }
        else
        {
            int[] codePoints = {code};
            String str = new String(codePoints, 0, 1);
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes)
            {
                builder.append('%');
                appendHexValue(builder, b);
            }
        }
    }

    private static void appendHexValue(Utf8StringBuilder builder, byte value)
    {
        byte d = (byte)((0xF0 & value) >> 4);
        builder.append((char)((d > 9 ? ('A' - 10) : '0') + d));
        d = (byte)(0xF & value);
        builder.append((char)((d > 9 ? ('A' - 10) : '0') + d));
    }

    private static boolean mustBeEncoded(int codepoint)
    {
        if (codepoint > 0x7F)
            return true;

        if ((codepoint <= 0x1F) || (codepoint == 0x7F))
            return true;

        if (codepoint == '"' || codepoint == '<' || codepoint == '>' || codepoint == '%' ||
            codepoint == '{' || codepoint == '}' || codepoint == '|' || codepoint == '\\' ||
            codepoint == '^' || codepoint == '`')
            return true;

        if ((codepoint == ' ') || (codepoint == '[') || (codepoint == ']'))
            return true;

        return ((codepoint == '?') || (codepoint == '#'));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(EncodePathSafeEncodingBenchmark.class.getSimpleName())
//            .addProfiler(GCProfiler.class)
            .forks(1)
            .build();

        new Runner(opt).run();
    }
}
