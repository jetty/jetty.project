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
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompactPathRuleTest extends AbstractRuleTest
{
    public static Stream<Arguments> scenarios()
    {
        return Stream.of(
            // shouldn't change anything
            Arguments.of("/foo", null, "/foo", null, "/foo"),
            Arguments.of("/", null, "/", null, "/"),
            // simple compact path
            Arguments.of("////foo", null, "/foo", null, "/foo"),
            // with simple query
            Arguments.of("//foo//bar", "a=b", "/foo/bar", "a=b", "/foo/bar?a=b"),
            // with query that has double slashes (should preserve slashes in query)
            Arguments.of("//foo//bar", "a=b//c", "/foo/bar", "a=b//c", "/foo/bar?a=b//c")
        );
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCompactPathRule(String inputPath, String inputQuery, String expectedPath, String expectedQuery, String expectedPathQuery) throws Exception
    {
        httpConfig.setUriCompliance(UriCompliance.UNSAFE);
        CompactPathRule rule = new CompactPathRule();
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, request.getHttpURI().getPathQuery(), callback);
            }
        });


        String request = """
            GET %s HTTP/1.1
            Host: localhost
                        
            """.formatted(HttpURI.build().path(inputPath).query(inputQuery));

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        System.err.println(response.getReason());
        assertEquals(HttpStatus.OK_200, response.getStatus());
        HttpURI.Mutable result = HttpURI.build(response.getContent());
        assertEquals(expectedPath, result.getPath());
        assertEquals(expectedQuery, result.getQuery());
        assertEquals(expectedPathQuery, result.getPathQuery());
    }
}
