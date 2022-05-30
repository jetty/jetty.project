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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RewritePatternRuleTest extends AbstractRuleTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of("/", "/replace", "/foo/bar", "/replace"),
            Arguments.of("/*", "/replace/foo/bar", "/foo/bar", "/replace/foo/bar/foo/bar"),
            Arguments.of("/foo/*", "/replace/bar", "/foo/bar", "/replace/bar/bar"),
            Arguments.of("/foo/bar", "/replace", "/foo/bar", "/replace"),
            Arguments.of("*.txt", "/replace", "/foo/bar.txt", "/replace"),
            Arguments.of("/foo/*", "/replace", "/foo/bar/%20x", "/replace/bar/%20x"),
            Arguments.of("/old/context", "/replace?given=param", "/old/context", "/replace?given=param")
        );
    }

    private void start(RewritePatternRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put("X-URI", request.getHttpURI().getPathQuery());
                callback.succeeded();
            }
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testRewritePatternRule(String pattern, String replacement, String inputURI, String expectURI) throws Exception
    {
        RewritePatternRule rule = new RewritePatternRule(pattern, replacement);
        start(rule);

        String request = """
            GET $U HTTP/1.1
            Host: localhost
            Connection: close
                        
            """.replace("$U", inputURI);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(expectURI, response.get("X-URI"), "X-URI response header value");
    }

    @Test
    public void testRequestWithQueryString() throws Exception
    {
        String replacement = "/replace";
        RewritePatternRule rule = new RewritePatternRule("/context", replacement);
        start(rule);

        String query = "a=b";
        String request = """
            GET /context?$Q HTTP/1.1
            Host: localhost
                        
            """.replace("$Q", query);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(replacement + "?" + query, response.get("X-URI"));
    }

    @Test
    public void testRequestAndReplacementWithQueryString() throws Exception
    {
        String replacementPath = "/replace";
        String replacementQuery = "c=d";
        RewritePatternRule rule = new RewritePatternRule("/context", replacementPath + "?" + replacementQuery);
        start(rule);

        String query = "a=b";
        String request = """
            GET /context?$Q HTTP/1.1
            Host: localhost
                        
            """.replace("$Q", query);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(replacementPath + "?" + query + "&" + replacementQuery, response.get("X-URI"));
    }
}
