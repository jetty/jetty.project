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
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewriteRegexRuleTest extends AbstractRuleTest
{
    public static Stream<Arguments> scenarios()
    {
        return Stream.of(
            // Simple replacement
            new Scenario("/foo0/bar", "/.*", "/replace", "/replace", null),
            // Non-matching rule (no replacement done)
            new Scenario("/foo2/bar", "/xxx.*", "/replace", "/foo2/bar", null),
            // Replacement with named references
            new Scenario("/foo1/bar?n=v", "^/.*\\?(?<query>.*)$", "/replace?${query}", "/replace", "n=v"),
            // group of everything after last slash
            new Scenario("/foo3/bar", "/(.*)/(.*)", "/$2/$1/xxx", "/bar/foo3/xxx", null),
            // TODO: path is not encoded when it reaches the X-Path handler
            new Scenario("/f%20o3/bar", "/(.*)/(.*)", "/$2/$1/xxx", "/bar/f%20o3/xxx", null),
            new Scenario("/foo4/bar", "/(.*)/(.*)", "/test?p2=$2&p1=$1", "/test", "p2=bar&p1=foo4"),
            new Scenario("/foo4.2/bar/zed", "/(.*)/(.*)", "/test?p2=$2&p1=$1", "/test", "p2=zed&p1=foo4.2/bar"),
            new Scenario("/foo4.3/bar/zed", "/([^/]*)/([^/]*)/.*", "/test?p2=$2&p1=$1", "/test", "p2=bar&p1=foo4.3"),
            // Example of bad regex group (accidentally covered query)
            new Scenario("/foo4.4/bar?x=y", "/(.*)/(.*)", "/test?p2=$2&p1=$1", "/test", "p2=bar?x=y&p1=foo4.4"),
            // Fixed Example of above bad regex group (covered query properly)
            new Scenario("/foo4.5/bar?x=y", "/([^/]*)/([^/?]*).*", "/test?p2=$2&p1=$1", "/test", "p2=bar&p1=foo4.5"),
            // specific regex groups
            new Scenario("/foo5/bar", "^/(foo5)/(.*)(bar)", "/$3/$1/xxx$2", "/bar/foo5/xxx", null),
            // target input with raw "$"
            new Scenario("/foo6/$bar", "/.*", "/replace", "/replace", null),
            // target input with raw "$", and replacement with "$" character
            new Scenario("/foo6/$bar", "/.*", "/\\$replace", "/$replace", null),
            new Scenario("/fooA/$bar", "/fooA/(.*)", "/$1/replace", "/$bar/replace", null),
            new Scenario("/fooB/bar/info", "/fooB/(NotHere)?([^/]*)/(.*)", "/$3/other?p1=$2", "/info/other", "p1=bar"),
            new Scenario("/fooC/bar/info", "/fooC/(NotHere)?([^/]*)/([^?]*)(?:\\?|/\\?|/|)(?<query>.*)", "/$3/other?p1=$2&${query}", "/info/other", "p1=bar&"),
            new Scenario("/fooD/bar/info?n=v", "/fooD/(NotHere)?([^/]*)/([^?]*)(?:\\?|/\\?|/|)(?<query>.*)", "/$3/other?p1=$2&${query}", "/info/other", "p1=bar&n=v"),
            new Scenario("/fooE/bar/info?n=v", "/fooE/(NotHere)?([^/]*)/([^?]*)(?:\\?|/\\?|/|)(?<query>.*)", "/$3/other?p1=$2", "/info/other", "p1=bar")
        ).map(Arguments::of);
    }

    private void start(RewriteRegexRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                HttpURI httpURI = request.getHttpURI();
                response.getHeaders().put("X-Path", httpURI.getPath());
                if (httpURI.getQuery() != null)
                    response.getHeaders().put("X-Query", httpURI.getQuery());
                callback.succeeded();
                return true;
            }
        });
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriEnabled(Scenario scenario) throws Exception
    {
        RewriteRegexRule rule = new RewriteRegexRule(scenario.regex, scenario.replacement);
        start(rule);

        String request = """
            GET $T HTTP/1.1
            Host: localhost
                        
            """.replace("$T", scenario.pathQuery);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus(), "Response status code");
        assertEquals(scenario.expectedPath, response.get("X-Path"), "Response X-Path header value");
        if (scenario.expectedQuery != null)
            assertEquals(scenario.expectedQuery, response.get("X-Query"), "Response X-Query header value");
    }

    public static Stream<Arguments> inputPathQueries()
    {
        return Stream.of(
            Arguments.of("/foo/bar", "/test?&p2=bar&p1=foo"),
            Arguments.of("/foo/bar/", "/test?&p2=bar&p1=foo"),
            Arguments.of("/foo/bar?", "/test?&p2=bar&p1=foo"),
            Arguments.of("/foo/bar/?", "/test?&p2=bar&p1=foo"),
            Arguments.of("/foo/bar?a=b", "/test?a=b&p2=bar&p1=foo"),
            Arguments.of("/foo/bar/?a=b", "/test?a=b&p2=bar&p1=foo")
        );
    }

    @ParameterizedTest
    @MethodSource("inputPathQueries")
    public void testRegexOptionalTargetQuery(String target, String expectedResult) throws Exception
    {
        String regex = "^/([^/]*)/([^/\\?]*)(?:\\?|/\\?|/|)(?<query>.*)$";
        String replacement = "/test?${query}&p2=$2&p1=$1";
        RewriteRegexRule rule = new RewriteRegexRule(regex, replacement);
        start(rule);

        String request = """
            GET $T HTTP/1.1
            Host: localhost
                        
            """.replace("$T", target);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus(), "Response status code");
        String result = response.get("X-Path");
        String query = response.get("X-Query");
        if (StringUtil.isNotBlank(query))
            result = result + '?' + query;

        assertThat(result, is(expectedResult));
    }

    private record Scenario(String pathQuery, String regex, String replacement, String expectedPath, String expectedQuery)
    {
    }
}
