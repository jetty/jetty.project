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

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewriteRegexRuleTest extends AbstractRuleTestCase
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

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestUriEnabled(Scenario scenario) throws Exception
    {
        start(false);
        RewriteRegexRule rule = new RewriteRegexRule();

        reset();
        _request.setHttpURI(HttpURI.build(_request.getHttpURI()));

        rule.setRegex(scenario.regex);
        rule.setReplacement(scenario.replacement);

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), scenario.uriPathQuery, null, scenario.queryString));

        String result = rule.matchAndApply(scenario.uriPathQuery, _request, _response);
        assertEquals(scenario.expectedRequestURI, result);
        rule.applyURI(_request, scenario.uriPathQuery, result);

        if (result != null)
        {
            assertEquals(scenario.expectedRequestURI, _request.getRequestURI());
            assertEquals(scenario.expectedQueryString, _request.getQueryString());
        }

        if (scenario.expectedQueryString != null)
        {
            MultiMap<String> params = new MultiMap<String>();
            UrlEncoded.decodeTo(scenario.expectedQueryString, params, StandardCharsets.UTF_8);

            for (String n : params.keySet())
            {
                assertEquals(params.getString(n), _request.getParameter(n));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testContainedRequestUriEnabled(Scenario scenario) throws Exception
    {
        start(false);
        RewriteRegexRule rule = new RewriteRegexRule();

        RuleContainer container = new RuleContainer();
        container.setRewriteRequestURI(true);
        container.addRule(rule);

        reset();
        rule.setRegex(scenario.regex);
        rule.setReplacement(scenario.replacement);

        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), scenario.uriPathQuery, null, scenario.queryString));
        _request.getAttributes().clearAttributes();

        String result = container.apply(URIUtil.decodePath(scenario.uriPathQuery), _request, _response);
        assertEquals(URIUtil.decodePath(scenario.expectedRequestURI == null ? scenario.uriPathQuery : scenario.expectedRequestURI), result);
        assertEquals(scenario.expectedRequestURI == null ? scenario.uriPathQuery : scenario.expectedRequestURI, _request.getRequestURI());
        assertEquals(scenario.expectedQueryString, _request.getQueryString());
    }

    private static class Scenario
    {
        String uriPathQuery;
        String queryString;
        String regex;
        String replacement;
        String expectedRequestURI;
        String expectedQueryString;

        public Scenario(String uriPathQuery, String queryString, String regex, String replacement, String expectedRequestURI, String expectedQueryString)
        {
            this.uriPathQuery = uriPathQuery;
            this.queryString = queryString;
            this.regex = regex;
            this.replacement = replacement;
            this.expectedRequestURI = expectedRequestURI;
            this.expectedQueryString = expectedQueryString;
        }

        @Override
        public String toString()
        {
            return String.format("%s?%s>%s|%s", uriPathQuery, queryString, regex, replacement);
        }
    }
}
