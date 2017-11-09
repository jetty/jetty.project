//  ========================================================================
//  Copyright (c) 2017 Juan F. Codagnone
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
package org.eclipse.jetty.servlets;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@BenchmarkMode({ Mode.Throughput })
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class CrossOriginFilterBenchmark 
{
    // cases to use for the benchmark
    @SuppressWarnings("unchecked")
    private static final List<String> [] cases = new List[] 
    {
        // one method
/* 0 */ Arrays.asList("GET"),
        // two methods
/* 1 */ Arrays.asList("GET", "POST"),
        // three methods, this is the default
/* 2 */ Arrays.asList("GET", "POST", "HEAD"),
        // this is another configuracion pausible 
/* 3 */ Arrays.asList("GET", "POST", "HEAD", "PUT","DELETE"),
        // this is an insame configuration, every registered methods, 2017-04-14 version of
        // https://www.iana.org/assignments/http-methods/http-methods.xhtml 
/* 4 */ Arrays.asList("ACL", "BASELINE-CONTROL", "BIND", 
        "CHECKIN", "CHECKOUT", "CONNECT", "COPY", "DELETE", "GET", "HEAD", "LABEL", "LINK", "LOCK", 
        "MERGE", "MKACTIVITY", "MKCALENDAR", "MKCOL", "MKREDIRECTREF", "MKWORKSPACE", "MOVE", "OPTIONS", 
        "ORDERPATCH", "PATCH", "POST", "PRI", "PROPFIND", "PROPPATCH", "PUT", "REBIND", "REPORT", "SEARCH", 
        "TRACE", "UNBIND", "UNCHECKOUT", "UNLINK", "UNLOCK", "UPDATE", "UPDATEREDIRECTREF", "VERSION-CONTROL"),
    };
    
    // pattern version of the cases
    private static final Pattern[]   PATTERNS = Stream.of(cases)
                                                     .map(CrossOriginFilter::patternize)
                                                     .toArray(Pattern[]::new);
    
    // method to use for the HIT benchmark: the last one that the user wrote
    private static final String[]  HIT_METHOD = Stream.of(cases).map( l -> l.get(l.size() -1))
                                                     .toArray(String[]::new);
    
    // method to use in MISS benchmark
    private static final String MISS_METHOD   = "FOO";
    
    @Param({"0", "1", "2", "3", "4"})
    public int arg;

    @SuppressWarnings("unchecked")
    private static final Collection<String>[] TREE_SETS = Stream.of(cases)
                                                     .map(TreeSet::new)
                                                     .toArray(Collection[]::new);

    @SuppressWarnings("unchecked")
    private static final Collection<String>[] HASHSET_SETS = Stream.of(cases)
                                                     .map(HashSet::new)
                                                     .toArray(Collection[]::new);

    
    @Benchmark
    public boolean pattern_hit()  
    {
        return CrossOriginFilter.isMethodAllowed(PATTERNS[arg], HIT_METHOD[arg]);
    }
    
    @Benchmark
    public boolean pattern_miss()  
    {
        return CrossOriginFilter.isMethodAllowed(PATTERNS[arg], MISS_METHOD);
    }
    
    @Benchmark
    public boolean contains_hit()  
    {
        return cases[arg].contains(HIT_METHOD[arg]);
    }
    
    public boolean contains_hits()  
    {
        return cases[arg].contains(HIT_METHOD[arg]);
    }
    
    @Benchmark
    public boolean contains_miss()  
    {
        return cases[arg].contains(MISS_METHOD); 
    }
    
    @Benchmark
    public boolean treeset_contains_hits()  
    {
        return TREE_SETS[arg].contains(HIT_METHOD[arg]);
    }
    
    @Benchmark
    public boolean treeset_contains_miss()  
    {
        return TREE_SETS[arg].contains(MISS_METHOD); 
    }
    
    @Benchmark
    public boolean hashset_contains_hits()  
    {
        return HASHSET_SETS[arg].contains(HIT_METHOD[arg]);
    }
    
    @Benchmark
    public boolean hashset_contains_miss()  
    {
        return HASHSET_SETS[arg].contains(MISS_METHOD); 
    }
    
    public static void main(String[] args) throws RunnerException 
    {
        final Options opt = new OptionsBuilder()
                          .include(CrossOriginFilterBenchmark.class.getSimpleName())
                          .forks(1)
                          .build();
        new Runner(opt).run();
    }
}