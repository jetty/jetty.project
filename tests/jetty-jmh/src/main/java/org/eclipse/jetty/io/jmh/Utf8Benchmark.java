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

package org.eclipse.jetty.io.jmh;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@Threads(1)
@Fork(1)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class Utf8Benchmark
{
    // Each string is about 450 characters long.
    private static final Map<String, String> STRINGS_MAP = new HashMap<>()
    {{
        put("ASCII", """
            Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor
            incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud
            exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute
            irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat
            nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa
            qui officia deserunt mollit anim id est laborum.""");
        put("FR", """
            J'ai su là-bas que, pour quelques emplettes,
            Éliante est sortie, et Célimène aussi ;
            Mais comme l'on m'a dit que vous étiez ici,
            J'ai monté pour vous dire, et d'un coeur véritable,
            Que j'ai conçu pour vous une estime incroyable,
            Et que, depuis longtemps, cette estime m'a mis
            Dans un ardent désir d'être de vos amis.
            Oui, mon coeur au mérite aime à rendre justice,
            Et je brûle qu'un noeud d'amitié nous unisse :
            Je crois qu'un ami chaud, et de ma qualité""");
        put("JA", """
            参加希望の方は今すぐ登録してください。この会議では、グローバルなインタネット、Unicode、
            ソフトウェアの国際化およびローカリゼーション、OSおよびアプリケーションでのUnicode
            のインプリメンテーション、フォント、テキスト表示、マルチ言語コンピューティングにおける業界の専門家が集まります。
            参加希望の方は今すぐ登録してください。この会議では、グローバルなインタネット、Unicode
            、ソフトウェアの国際化およびローカリゼーション、OSおよびアプリケーションでのUnicode
            のインプリメンテーション、フォント、テキスト表示、マルチ言語コンピューティングにおける業界の専門家が集まります。
            参加希望の方は今すぐ登録してください。この会議では、グローバルなインタネット、Unicode
            、ソフトウェアの国際化およびローカリゼーション、OSおよびアプリケーションでのUnicode
            のインプリメンテーション、フォント、テキスト表示、マルチ言語コンピューティングにおける業界の専門家が集まります。""");
    }};

    @Param({"ASCII", "FR", "JA"})
    String locale;

    String utf8Content;

    @Setup
    public void setUp()
    {
        utf8Content = STRINGS_MAP.get(locale);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public Object testEncode()
    {
        return StandardCharsets.UTF_8.encode(utf8Content);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public Object testWrapGetBytes()
    {
        return ByteBuffer.wrap(utf8Content.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(Utf8Benchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .build();

        new Runner(opt).run();
    }
}
