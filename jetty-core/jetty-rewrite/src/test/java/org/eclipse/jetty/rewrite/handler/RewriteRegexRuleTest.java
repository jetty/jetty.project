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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewriteRegexRuleTest extends AbstractRuleTest
{
    public static Stream<Arguments> scenarios()
    {
        return Stream.of(
            new Scenario("/foo0/bar", null, ".*", "/replace", "/replace", null),
            new Scenario("/foo1/bar", "n=v", ".*", "/replace", "/replace", "n=v"),
            new Scenario("/foo2/bar", null, "/xxx.*", "/replace", null, null),
            new Scenario("/foo3/bar", null, "/(.*)/(.*)", "/$2/$1/xxx", "/bar/foo3/xxx", null),
            new Scenario("/f%20o3/bar", null, "/(.*)/(.*)", "/$2/$1/xxx", "/bar/f%20o3/xxx", null),
            new Scenario("/foo4/bar", null, "/(.*)/(.*)", "/test?p2=$2&p1=$1", "/test", "p2=bar&p1=foo4"),
            new Scenario("/foo5/bar", "n=v", "/(.*)/(.*)", "/test?p2=$2&p1=$1", "/test", "n=v&p2=bar&p1=foo5"),
            new Scenario("/foo6/bar", null, "/(.*)/(.*)", "/foo6/bar?p2=$2&p1=$1", "/foo6/bar", "p2=bar&p1=foo6"),
            new Scenario("/foo7/bar", "n=v", "/(.*)/(.*)", "/foo7/bar?p2=$2&p1=$1", "/foo7/bar", "n=v&p2=bar&p1=foo7"),
            new Scenario("/foo8/bar", null, "/(foo8)/(.*)(bar)", "/$3/$1/xxx$2", "/bar/foo8/xxx", null),
            new Scenario("/foo9/$bar", null, ".*", "/$replace", "/$replace", null),
            new Scenario("/fooA/$bar", null, "/fooA/(.*)", "/$1/replace", "/$bar/replace", null),
            new Scenario("/fooB/bar/info", null, "/fooB/(NotHere)?([^/]*)/(.*)", "/$3/other?p1=$2", "/info/other", "p1=bar"),
            new Scenario("/fooC/bar/info", null, "/fooC/(NotHere)?([^/]*)/(.*)", "/$3/other?p1=$2&$Q", "/info/other", "p1=bar&"),
            new Scenario("/fooD/bar/info", "n=v", "/fooD/(NotHere)?([^/]*)/(.*)", "/$3/other?p1=$2&$Q", "/info/other", "p1=bar&n=v"),
            new Scenario("/fooE/bar/info", "n=v", "/fooE/(NotHere)?([^/]*)/(.*)", "/$3/other?p1=$2", "/info/other", "n=v&p1=bar")
        ).map(Arguments::of);
    }

    private void start(RewriteRegexRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                HttpURI httpURI = request.getHttpURI();
                response.setHeader("X-Path", httpURI.getPath());
                if (httpURI.getQuery() != null)
                    response.setHeader("X-Query", httpURI.getQuery());
                callback.succeeded();
            }
        });
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriEnabled(Scenario scenario) throws Exception
    {
        RewriteRegexRule rule = new RewriteRegexRule(scenario.regex, scenario.replacement);
        start(rule);

        String target = scenario.path;
        if (scenario.query != null)
            target += "?" + scenario.query;
        String request = """
            GET $T HTTP/1.1
            Host: localhost
                        
            """.replace("$T", target);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(scenario.expectedPath, response.get("X-Path"));
        if (scenario.expectedQuery != null)
            assertEquals(scenario.expectedQuery, response.get("X-Query"));

    }

    private record Scenario(String path, String query, String regex, String replacement, String expectedPath, String expectedQuery)
    {
    }
}
