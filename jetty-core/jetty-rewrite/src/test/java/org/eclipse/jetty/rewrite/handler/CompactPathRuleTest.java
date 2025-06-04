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
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompactPathRuleTest extends AbstractRuleTest
{
    public static Stream<Arguments> scenarios()
    {
        return Stream.of(
            // shouldn't change anything
            Arguments.of(false, false, "/foo", null, "/foo", "", "/foo"),
            Arguments.of(false, false, "/", null, "/", "", "/"),
            // simple compact path
            Arguments.of(false, false, "////foo", null, "/foo", "", "/foo"),
            // with simple query
            Arguments.of(false, false, "//foo//bar", "a=b", "/foo/bar", "a=b", "/foo/bar?a=b"),
            // with query that has double slashes (should preserve slashes in query)
            Arguments.of(false, false, "//foo//bar", "a=b//c", "/foo/bar", "a=b//c", "/foo/bar?a=b//c"),
            // with ambiguous path parameter
            Arguments.of(false, false, "//foo/..;/bar", "a=b//c", "/bar", "a=b//c", "/bar?a=b//c"),
            // with ambiguous path separator
            Arguments.of(false, false, "//foo/b%2far", "a=b//c", "/foo/b%2Far", "a=b//c", "/foo/b%2Far?a=b//c"),
            Arguments.of(true, false, "//foo/b%2far", "a=b//c", "/foo/b/ar", "a=b//c", "/foo/b/ar?a=b//c"),
            // with ambiguous path encoding
            Arguments.of(false, false, "//foo/%2562ar", "a=b//c", "/foo/%2562ar", "a=b//c", "/foo/%2562ar?a=b//c"),
            Arguments.of(true, false, "//foo/%2562ar", "a=b//c", "/foo/%62ar", "a=b//c", "/foo/%62ar?a=b//c"),
            // with path above navigation
            Arguments.of(false, false, "/..%2f../context/index.html", null, "/..%2F../context/index.html", "", "/..%2F../context/index.html"),
            Arguments.of(false, true, "/..%2f../context/index.html", null, "/..%2F../context/index.html", "", "/..%2F../context/index.html"),
            Arguments.of(true, true, "/..%2f../context/index.html", null, "/", "", "/"),
            Arguments.of(false, false, "/a/%2e%2e/b/index.html", null, "/b/index.html", "", "/b/index.html"),
            Arguments.of(true, false, "/a/%2e%2e/b/index.html", null, "/b/index.html", "", "/b/index.html"),
            Arguments.of(false, true, "/a/%2e%2e/b/index.html", null, "/b/index.html", "", "/b/index.html"),
            Arguments.of(true, true, "/a/%2e%2e/b/index.html", null, "/b/index.html", "", "/b/index.html"),
            Arguments.of(false, false, "/a/%2e%2e/b/../c/index.html", null, "/c/index.html", "", "/c/index.html"),
            Arguments.of(true, false, "/a/%2e%2e/b/../c/index.html", null, "/c/index.html", "", "/c/index.html"),
            Arguments.of(false, true, "/a/%2e%2e/b/../c/index.html", null, "/c/index.html", "", "/c/index.html"),
            Arguments.of(true, true, "/a/%2e%2e/b/../c/index.html", null, "/c/index.html", "", "/c/index.html")
        );
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCompactPathRule(boolean decoding, boolean canonicalizing, String inputPath, String inputQuery, String expectedPath, String expectedQuery, String expectedPathQuery) throws Exception
    {
        _httpConfig.setUriCompliance(UriCompliance.UNSAFE);
        CompactPathRule rule = new CompactPathRule();
        rule.setDecoding(decoding);
        rule.setCanonicalizing(canonicalizing);
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

        String requestPath = inputPath;
        if (inputQuery != null)
            requestPath += "?" + inputQuery;

        String request = """
            GET %s HTTP/1.1
            Host: localhost
            
            """.formatted(requestPath);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
        Properties props = new Properties();
        try (ByteArrayInputStream in = new ByteArrayInputStream(response.getContentBytes()))
        {
            props.load(in);
            assertEquals(expectedPath, props.getProperty("uri.path"));
            assertEquals(expectedQuery, props.getProperty("uri.query"));
            assertEquals(expectedPathQuery, props.getProperty("uri.pathQuery"));

            boolean expectingAmbiguousPathSep = !decoding && inputPath.contains("%2f");
            boolean expectingAmbiguousPathEncoding = !decoding && inputPath.contains("%25");

            assertThat("uri.isAmbiguous", getBoolean(props, "uri.isAmbiguous"), is(expectingAmbiguousPathSep || expectingAmbiguousPathEncoding));
            assertThat("uri.hasViolations", getBoolean(props, "uri.hasViolations"), is(expectingAmbiguousPathSep || expectingAmbiguousPathEncoding));
            assertThat("uri.hasAmbiguousEmptySegment", getBoolean(props, "uri.hasAmbiguousEmptySegment"), is(false));
            assertThat("uri.hasAmbiguousEncoding", getBoolean(props, "uri.hasAmbiguousEncoding"), is(expectingAmbiguousPathEncoding));
            assertThat("uri.hasAmbiguousParameter", getBoolean(props, "uri.hasAmbiguousParameter"), is(false));
            assertThat("uri.hasAmbiguousSeparator", getBoolean(props, "uri.hasAmbiguousSeparator"), is(expectingAmbiguousPathSep));
            assertThat("uri.hasAmbiguousSegment", getBoolean(props, "uri.hasAmbiguousSegment"), is(false));
        }
    }

    private boolean getBoolean(Properties properties, String key)
    {
        return Boolean.parseBoolean(properties.getProperty(key, "false"));
    }

    /**
     * Tests where the navigation goes up beyond the root of the URL
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|',
        textBlock = """
            # decoding | canonicalizing | inputPath
            false | false | /../context/
            false | false | /%2e./context/
            false | false | /.%2e/context/
            false | false | /%2e%2e/context/
            true | false | /..%2fcontext/
            """)
    public void testCompactPathRuleResultsInBadMessage(boolean decoding, boolean canonicalizing, String inputPath) throws Exception
    {
        _httpConfig.setUriCompliance(UriCompliance.UNSAFE);
        CompactPathRule rule = new CompactPathRule();
        rule.setDecoding(decoding);
        rule.setCanonicalizing(canonicalizing);
        _rewriteHandler.addRule(rule);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, "Shouldn't reach here", callback);
                return true;
            }
        });

        String request = """
            GET %s HTTP/1.1
            Host: localhost
            
            """.formatted(inputPath);

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }
}
