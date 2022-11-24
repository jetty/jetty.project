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

import java.util.regex.Matcher;
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

public class RegexRuleTest extends AbstractRuleTest
{
    public static Stream<Arguments> matches()
    {
        return Stream.of(
            // regex: *.jsp
            Arguments.of("/.*.jsp", "/hello.jsp"),
            Arguments.of("/.*.jsp", "/abc/hello.jsp"),

            // regex: /abc or /def
            Arguments.of("/abc|/def", "/abc"),
            Arguments.of("/abc|/def", "/def"),

            // regex: *.do or *.jsp
            Arguments.of(".*\\.do|.*\\.jsp", "/hello.do"),
            Arguments.of(".*\\.do|.*\\.jsp", "/hello.jsp"),
            Arguments.of(".*\\.do|.*\\.jsp", "/abc/hello.do"),
            Arguments.of(".*\\.do|.*\\.jsp", "/abc/hello.jsp"),

            Arguments.of("/abc/.*.htm|/def/.*.htm", "/abc/hello.htm"),
            Arguments.of("/abc/.*.htm|/def/.*.htm", "/abc/def/hello.htm"),

            // regex: /abc/*.jsp
            Arguments.of("/abc/.*.jsp", "/abc/hello.jsp"),
            Arguments.of("/abc/.*.jsp", "/abc/def/hello.jsp")
        );
    }

    public static Stream<Arguments> noMatches()
    {
        return Stream.of(
            Arguments.of("/abc/.*.jsp", "/hello.jsp")
        );
    }

    private void start(RegexRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });
    }

    @ParameterizedTest
    @MethodSource("matches")
    public void testTrueMatch(String pattern, String uri) throws Exception
    {
        TestRegexRule rule = new TestRegexRule(pattern);
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
        TestRegexRule rule = new TestRegexRule(pattern);
        start(rule);

        String request = """
            GET $U HTTP/1.1
            Host: localhost
                        
            """.replace("$U", uri);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertFalse(rule._applied);
    }

    private static class TestRegexRule extends RegexRule
    {
        private boolean _applied;

        public TestRegexRule(String pattern)
        {
            super(pattern);
        }

        @Override
        public Processor apply(Processor input, Matcher matcher)
        {
            _applied = true;
            return input;
        }
    }
}
