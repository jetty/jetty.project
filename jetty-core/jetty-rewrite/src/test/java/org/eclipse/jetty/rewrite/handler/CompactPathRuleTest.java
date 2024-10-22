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

package org.eclipse.jetty.rewrite.handler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
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
            Arguments.of("/foo", null, "/foo", "", "/foo"),
            Arguments.of("/", null, "/", "", "/"),
            // simple compact path
            Arguments.of("////foo", null, "/foo", "", "/foo"),
            // with simple query
            Arguments.of("//foo//bar", "a=b", "/foo/bar", "a=b", "/foo/bar?a=b"),
            // with query that has double slashes (should preserve slashes in query)
            Arguments.of("//foo//bar", "a=b//c", "/foo/bar", "a=b//c", "/foo/bar?a=b//c"),
            // with ambiguous path parameter
            Arguments.of("//foo/..;/bar", "a=b//c", "/bar", "a=b//c", "/bar?a=b//c"),
            // with ambiguous path separator (not changed)
            Arguments.of("//foo/b%2far", "a=b//c", "/foo/b%2Far", "a=b//c", "/foo/b%2Far?a=b//c"),
            // with ambiguous path encoding (not changed)
            Arguments.of("//foo/%2562ar", "a=b//c", "/foo/%2562ar", "a=b//c", "/foo/%2562ar?a=b//c")
        );
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCompactPathRule(String inputPath, String inputQuery, String expectedPath, String expectedQuery, String expectedPathQuery) throws Exception
    {
        _httpConfig.setUriCompliance(UriCompliance.UNSAFE);
        CompactPathRule rule = new CompactPathRule();
        _rewriteHandler.addRule(rule);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Properties props = new Properties();
                HttpURI httpURI = request.getHttpURI();
                props.setProperty("uri.path", of(httpURI.getPath()));
                props.setProperty("uri.query", of(httpURI.getQuery()));
                props.setProperty("uri.pathQuery", of(httpURI.getPathQuery()));
                props.setProperty("uri.hasViolations", of(httpURI.hasViolations()));
                props.setProperty("uri.isAmbiguous", of(httpURI.isAmbiguous()));
                props.setProperty("uri.hasAmbiguousEmptySegment", of(httpURI.hasAmbiguousEmptySegment()));
                props.setProperty("uri.hasAmbiguousEncoding", of(httpURI.hasAmbiguousEncoding()));
                props.setProperty("uri.hasAmbiguousParameter", of(httpURI.hasAmbiguousParameter()));
                props.setProperty("uri.hasAmbiguousSeparator", of(httpURI.hasAmbiguousSeparator()));
                props.setProperty("uri.hasAmbiguousSegment", of(httpURI.hasAmbiguousSegment()));
                try (ByteArrayOutputStream out = new ByteArrayOutputStream())
                {
                    props.store(out, "HttpURI State");
                    response.write(true, ByteBuffer.wrap(out.toByteArray()), callback);
                }
                catch (IOException e)
                {
                    callback.failed(e);
                }
                return true;
            }

            private String of(Object obj)
            {
                if (obj == null)
                    return "";
                if (obj instanceof Boolean)
                    return Boolean.toString((Boolean)obj);
                return Objects.toString(obj);
            }
        });


        String request = """
            GET %s HTTP/1.1
            Host: localhost
            
            """.formatted(HttpURI.build().path(inputPath).query(inputQuery));

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        Properties props = new Properties();
        try (ByteArrayInputStream in = new ByteArrayInputStream(response.getContentBytes()))
        {
            props.load(in);
            assertEquals(expectedPath, props.getProperty("uri.path"));
            assertEquals(expectedQuery, props.getProperty("uri.query"));
            assertEquals(expectedPathQuery, props.getProperty("uri.pathQuery"));

            boolean ambiguousPathSep = inputPath.contains("%2f");
            boolean ambiguousPathEncoding = inputPath.contains("%25");

            assertEquals(Boolean.toString(ambiguousPathSep || ambiguousPathEncoding), props.getProperty("uri.isAmbiguous"));
            assertEquals(Boolean.toString(ambiguousPathSep || ambiguousPathEncoding), props.getProperty("uri.hasViolations"));
            assertEquals("false", props.getProperty("uri.hasAmbiguousEmptySegment"));
            assertEquals(Boolean.toString(ambiguousPathEncoding), props.getProperty("uri.hasAmbiguousEncoding"));
            assertEquals("false", props.getProperty("uri.hasAmbiguousParameter"));
            assertEquals(Boolean.toString(ambiguousPathSep), props.getProperty("uri.hasAmbiguousSeparator"));
            assertEquals("false", props.getProperty("uri.hasAmbiguousSegment"));
        }
    }
}
