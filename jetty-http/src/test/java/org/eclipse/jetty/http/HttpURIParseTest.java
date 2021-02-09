//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
//

package org.eclipse.jetty.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HttpURIParseTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(

            // Nothing but path
            Arguments.of("path", null, null, "-1", "path", null, null, null),
            Arguments.of("path/path", null, null, "-1", "path/path", null, null, null),
            Arguments.of("%65ncoded/path", null, null, "-1", "%65ncoded/path", null, null, null),

            // Basic path reference
            Arguments.of("/path/to/context", null, null, "-1", "/path/to/context", null, null, null),

            // Basic with encoded query
            Arguments.of("http://example.com/path/to/context;param?query=%22value%22#fragment", "http", "example.com", "-1", "/path/to/context;param", "param", "query=%22value%22", "fragment"),
            Arguments.of("http://[::1]/path/to/context;param?query=%22value%22#fragment", "http", "[::1]", "-1", "/path/to/context;param", "param", "query=%22value%22", "fragment"),

            // Basic with parameters and query
            Arguments.of("http://example.com:8080/path/to/context;param?query=%22value%22#fragment", "http", "example.com", "8080", "/path/to/context;param", "param", "query=%22value%22", "fragment"),
            Arguments.of("http://[::1]:8080/path/to/context;param?query=%22value%22#fragment", "http", "[::1]", "8080", "/path/to/context;param", "param", "query=%22value%22", "fragment"),

            // Path References
            Arguments.of("/path/info", null, null, null, "/path/info", null, null, null),
            Arguments.of("/path/info#fragment", null, null, null, "/path/info", null, null, "fragment"),
            Arguments.of("/path/info?query", null, null, null, "/path/info", null, "query", null),
            Arguments.of("/path/info?query#fragment", null, null, null, "/path/info", null, "query", "fragment"),
            Arguments.of("/path/info;param", null, null, null, "/path/info;param", "param", null, null),
            Arguments.of("/path/info;param#fragment", null, null, null, "/path/info;param", "param", null, "fragment"),
            Arguments.of("/path/info;param?query", null, null, null, "/path/info;param", "param", "query", null),
            Arguments.of("/path/info;param?query#fragment", null, null, null, "/path/info;param", "param", "query", "fragment"),
            Arguments.of("/path/info;a=b/foo;c=d", null, null, null, "/path/info;a=b/foo;c=d", "c=d", null, null), // TODO #405

            // Protocol Less (aka scheme-less) URIs
            Arguments.of("//host/path/info", null, "host", null, "/path/info", null, null, null),
            Arguments.of("//user@host/path/info", null, "host", null, "/path/info", null, null, null),
            Arguments.of("//user@host:8080/path/info", null, "host", "8080", "/path/info", null, null, null),
            Arguments.of("//host:8080/path/info", null, "host", "8080", "/path/info", null, null, null),

            // Host Less
            Arguments.of("http:/path/info", "http", null, null, "/path/info", null, null, null),
            Arguments.of("http:/path/info#fragment", "http", null, null, "/path/info", null, null, "fragment"),
            Arguments.of("http:/path/info?query", "http", null, null, "/path/info", null, "query", null),
            Arguments.of("http:/path/info?query#fragment", "http", null, null, "/path/info", null, "query", "fragment"),
            Arguments.of("http:/path/info;param", "http", null, null, "/path/info;param", "param", null, null),
            Arguments.of("http:/path/info;param#fragment", "http", null, null, "/path/info;param", "param", null, "fragment"),
            Arguments.of("http:/path/info;param?query", "http", null, null, "/path/info;param", "param", "query", null),
            Arguments.of("http:/path/info;param?query#fragment", "http", null, null, "/path/info;param", "param", "query", "fragment"),

            // Everything and the kitchen sink
            Arguments.of("http://user@host:8080/path/info;param?query#fragment", "http", "host", "8080", "/path/info;param", "param", "query", "fragment"),
            Arguments.of("xxxxx://user@host:8080/path/info;param?query#fragment", "xxxxx", "host", "8080", "/path/info;param", "param", "query", "fragment"),

            // No host, parameter with no content
            Arguments.of("http:///;?#", "http", null, null, "/;", "", "", ""),

            // Path with query that has no value
            Arguments.of("/path/info?a=?query", null, null, null, "/path/info", null, "a=?query", null),

            // Path with query alt syntax
            Arguments.of("/path/info?a=;query", null, null, null, "/path/info", null, "a=;query", null),

            // URI with host character
            Arguments.of("/@path/info", null, null, null, "/@path/info", null, null, null),
            Arguments.of("/user@path/info", null, null, null, "/user@path/info", null, null, null),
            Arguments.of("//user@host/info", null, "host", null, "/info", null, null, null),
            Arguments.of("//@host/info", null, "host", null, "/info", null, null, null),
            Arguments.of("@host/info", null, null, null, "@host/info", null, null, null),

            // Scheme-less, with host and port (overlapping with path)
            Arguments.of("//host:8080//", null, "host", "8080", "//", null, null, null),

            // File reference
            Arguments.of("file:///path/info", "file", null, null, "/path/info", null, null, null),
            Arguments.of("file:/path/info", "file", null, null, "/path/info", null, null, null),

            // Bad URI (no scheme, no host, no path)
            Arguments.of("//", null, null, null, null, null, null, null),

            // Simple localhost references
            Arguments.of("http://localhost/", "http", "localhost", null, "/", null, null, null),
            Arguments.of("http://localhost:8080/", "http", "localhost", "8080", "/", null, null, null),
            Arguments.of("http://localhost/?x=y", "http", "localhost", null, "/", null, "x=y", null),

            // Simple path with parameter
            Arguments.of("/;param", null, null, null, "/;param", "param", null, null),
            Arguments.of(";param", null, null, null, ";param", "param", null, null),

            // Simple path with query
            Arguments.of("/?x=y", null, null, null, "/", null, "x=y", null),
            Arguments.of("/?abc=test", null, null, null, "/", null, "abc=test", null),

            // Simple path with fragment
            Arguments.of("/#fragment", null, null, null, "/", null, null, "fragment"),

            // Simple IPv4 host with port (default path)
            Arguments.of("http://192.0.0.1:8080/", "http", "192.0.0.1", "8080", "/", null, null, null),

            // Simple IPv6 host with port (default path)

            Arguments.of("http://[2001:db8::1]:8080/", "http", "[2001:db8::1]", "8080", "/", null, null, null),
            // IPv6 authenticated host with port (default path)

            Arguments.of("http://user@[2001:db8::1]:8080/", "http", "[2001:db8::1]", "8080", "/", null, null, null),

            // Simple IPv6 host no port (default path)
            Arguments.of("http://[2001:db8::1]/", "http", "[2001:db8::1]", null, "/", null, null, null),

            // Scheme-less IPv6, host with port (default path)
            Arguments.of("//[2001:db8::1]:8080/", null, "[2001:db8::1]", "8080", "/", null, null, null),

            // Interpreted as relative path of "*" (no host/port/scheme/query/fragment)
            Arguments.of("*", null, null, null, "*", null, null, null),

            // Path detection Tests (seen from JSP/JSTL and <c:url> use)
            Arguments.of("http://host:8080/path/info?q1=v1&q2=v2", "http", "host", "8080", "/path/info", null, "q1=v1&q2=v2", null),
            Arguments.of("/path/info?q1=v1&q2=v2", null, null, null, "/path/info", null, "q1=v1&q2=v2", null),
            Arguments.of("/info?q1=v1&q2=v2", null, null, null, "/info", null, "q1=v1&q2=v2", null),
            Arguments.of("info?q1=v1&q2=v2", null, null, null, "info", null, "q1=v1&q2=v2", null),
            Arguments.of("info;q1=v1?q2=v2", null, null, null, "info;q1=v1", "q1=v1", "q2=v2", null),

            // Path-less, query only (seen from JSP/JSTL and <c:url> use)
            Arguments.of("?q1=v1&q2=v2", null, null, null, "", null, "q1=v1&q2=v2", null)
        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testParseString(String input, String scheme, String host, Integer port, String path, String param, String query, String fragment) throws Exception
    {
        HttpURI httpUri = new HttpURI(input);

        try
        {
            new URI(input);
            // URI is valid (per java.net.URI parsing)

            // Test case sanity check
            assertThat("[" + input + "] expected path (test case) cannot be null", path, notNullValue());

            // Assert expectations
            assertThat("[" + input + "] .scheme", httpUri.getScheme(), is(scheme));
            assertThat("[" + input + "] .host", httpUri.getHost(), is(host));
            assertThat("[" + input + "] .port", httpUri.getPort(), is(port == null ? -1 : port));
            assertThat("[" + input + "] .path", httpUri.getPath(), is(path));
            assertThat("[" + input + "] .param", httpUri.getParam(), is(param));
            assertThat("[" + input + "] .query", httpUri.getQuery(), is(query));
            assertThat("[" + input + "] .fragment", httpUri.getFragment(), is(fragment));
            assertThat("[" + input + "] .toString", httpUri.toString(), is(input));
        }
        catch (URISyntaxException e)
        {
            // Assert HttpURI values for invalid URI (such as "//")
            assertThat("[" + input + "] .scheme", httpUri.getScheme(), is(nullValue()));
            assertThat("[" + input + "] .host", httpUri.getHost(), is(nullValue()));
            assertThat("[" + input + "] .port", httpUri.getPort(), is(-1));
            assertThat("[" + input + "] .path", httpUri.getPath(), is(nullValue()));
            assertThat("[" + input + "] .param", httpUri.getParam(), is(nullValue()));
            assertThat("[" + input + "] .query", httpUri.getQuery(), is(nullValue()));
            assertThat("[" + input + "] .fragment", httpUri.getFragment(), is(nullValue()));
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testParseURI(String input, String scheme, String host, Integer port, String path, String param, String query, String fragment) throws Exception
    {
        URI javaUri = null;
        try
        {
            javaUri = new URI(input);
        }
        catch (URISyntaxException ignore)
        {
            // Ignore, as URI is invalid anyway
        }
        assumeTrue(javaUri != null, "Skipping, not a valid input URI");

        HttpURI httpUri = new HttpURI(javaUri);

        assertThat("[" + input + "] .scheme", httpUri.getScheme(), is(scheme));
        assertThat("[" + input + "] .host", httpUri.getHost(), is(host));
        assertThat("[" + input + "] .port", httpUri.getPort(), is(port == null ? -1 : port));
        assertThat("[" + input + "] .path", httpUri.getPath(), is(path));
        assertThat("[" + input + "] .param", httpUri.getParam(), is(param));
        assertThat("[" + input + "] .query", httpUri.getQuery(), is(query));
        assertThat("[" + input + "] .fragment", httpUri.getFragment(), is(fragment));

        assertThat("[" + input + "] .toString", httpUri.toString(), is(input));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCompareToJavaNetURI(String input, String scheme, String host, Integer port, String path, String param, String query, String fragment) throws Exception
    {
        URI javaUri = null;
        try
        {
            javaUri = new URI(input);
        }
        catch (URISyntaxException ignore)
        {
            // Ignore, as URI is invalid anyway
        }
        assumeTrue(javaUri != null, "Skipping, not a valid input URI");

        HttpURI httpUri = new HttpURI(javaUri);

        assertThat("[" + input + "] .scheme", httpUri.getScheme(), is(javaUri.getScheme()));
        assertThat("[" + input + "] .host", httpUri.getHost(), is(javaUri.getHost()));
        assertThat("[" + input + "] .port", httpUri.getPort(), is(javaUri.getPort()));
        assertThat("[" + input + "] .path", httpUri.getPath(), is(javaUri.getRawPath()));
        // Not Relevant for java.net.URI -- assertThat("["+input+"] .param", httpUri.getParam(), is(param));
        assertThat("[" + input + "] .query", httpUri.getQuery(), is(javaUri.getRawQuery()));
        assertThat("[" + input + "] .fragment", httpUri.getFragment(), is(javaUri.getFragment()));
        assertThat("[" + input + "] .toString", httpUri.toString(), is(javaUri.toASCIIString()));
    }
}
