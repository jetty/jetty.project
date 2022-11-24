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

package org.eclipse.jetty.rewrite.handler;

import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PatternRuleTest extends AbstractRuleTest
{
    public static Stream<Arguments> matches()
    {
        return Stream.of(
            Arguments.of("/abc", "/abc"),
            Arguments.of("/abc/", "/abc/"),

            Arguments.of("/abc/path/longer", "/abc/path/longer"),
            Arguments.of("/abc/path/longer/", "/abc/path/longer/"),

            Arguments.of("/abc/*", "/abc/hello.jsp"),
            Arguments.of("/abc/*", "/abc/a"),
            Arguments.of("/abc/*", "/abc/a/hello.jsp"),
            Arguments.of("/abc/*", "/abc/a/b"),
            Arguments.of("/abc/*", "/abc/a/b/hello.jsp"),
            Arguments.of("/abc/*", "/abc/a/b/c"),
            Arguments.of("/abc/*", "/abc/a/b/c/hello.jsp"),

            Arguments.of("/abc/def/*", "/abc/def/gf"),
            Arguments.of("/abc/def/*", "/abc/def/gf.html"),
            Arguments.of("/abc/def/*", "/abc/def/ghi"),
            Arguments.of("/abc/def/*", "/abc/def/ghi/"),
            Arguments.of("/abc/def/*", "/abc/def/ghi/hello.html"),

            Arguments.of("*.do", "/abc.do"),
            Arguments.of("*.do", "/abc/hello.do"),
            Arguments.of("*.do", "/abc/def/hello.do"),
            Arguments.of("*.do", "/abc/def/ghi/hello.do"),

            Arguments.of("*.jsp", "/abc.jsp"),
            Arguments.of("*.jsp", "/abc/hello.jsp"),
            Arguments.of("*.jsp", "/abc/def/hello.jsp"),
            Arguments.of("*.jsp", "/abc/def/ghi/hello.jsp"),

            Arguments.of("/", "/Other"),
            Arguments.of("/", "/Other/hello.do"),
            Arguments.of("/", "/Other/path"),
            Arguments.of("/", "/Other/path/hello.do"),
            Arguments.of("/", "/abc/def"),

            Arguments.of("/abc:/def", "/abc:/def")
        );
    }

    public static Stream<Arguments> noMatches()
    {
        return Stream.of(
            Arguments.of("/abc", "/abcd"),
            Arguments.of("/abc/", "/abcd/"),

            Arguments.of("/abc/path/longer", "/abc/path/longer/"),
            Arguments.of("/abc/path/longer", "/abc/path/longer1"),
            Arguments.of("/abc/path/longer/", "/abc/path/longer"),
            Arguments.of("/abc/path/longer/", "/abc/path/longer1/"),

            Arguments.of("/*.jsp", "/hello.jsp"),
            Arguments.of("/abc/*.jsp", "/abc/hello.jsp"),

            Arguments.of("*.jsp", "/hello.1jsp"),
            Arguments.of("*.jsp", "/hello.jsp1"),
            Arguments.of("*.jsp", "/hello.do"),

            Arguments.of("*.jsp", "/abc/hello.do"),
            Arguments.of("*.jsp", "/abc/def/hello.do"),
            Arguments.of("*.jsp", "/abc.do")
        );
    }

    private void start(PatternRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });
    }

    @ParameterizedTest
    @MethodSource("matches")
    public void testTrueMatch(String pattern, String uri) throws Exception
    {
        TestPatternRule rule = new TestPatternRule(pattern);
        start(rule);

        String request = """
            GET $U HTTP/1.1
            Host: localhost
                        
            """.replace("$U", uri);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(rule._applied);
    }

    @ParameterizedTest
    @MethodSource("noMatches")
    public void testFalseMatch(String pattern, String uri) throws Exception
    {
        TestPatternRule rule = new TestPatternRule(pattern);
        start(rule);

        String request = """
            GET $U HTTP/1.1
            Host: localhost
                        
            """.replace("$U", uri);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertFalse(rule._applied);
    }

    private static class TestPatternRule extends PatternRule
    {
        private boolean _applied;

        private TestPatternRule(String pattern)
        {
            super(pattern);
        }

        @Override
        public Processor apply(Processor input)
        {
            _applied = true;
            return input;
        }
    }
}
