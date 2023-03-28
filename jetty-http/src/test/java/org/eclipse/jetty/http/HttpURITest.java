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

package org.eclipse.jetty.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.stream.Stream;

import org.eclipse.jetty.http.UriCompliance.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class HttpURITest
{
    @Test
    public void testBuilder()
    {
        HttpURI uri = HttpURI.build()
            .scheme("http")
            .user("user:password")
            .host("host")
            .port(8888)
            .path("/ignored/../p%61th;ignored/info")
            .param("param")
            .query("query=value")
            .asImmutable();

        assertThat(uri.getScheme(), is("http"));
        assertThat(uri.getUser(), is("user:password"));
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(8888));
        assertThat(uri.getPath(), is("/ignored/../p%61th;ignored/info;param"));
        assertThat(uri.getDecodedPath(), is("/path/info"));
        assertThat(uri.getParam(), is("param"));
        assertThat(uri.getQuery(), is("query=value"));
        assertThat(uri.getAuthority(), is("host:8888"));
        assertThat(uri.toString(), is("http://user:password@host:8888/ignored/../p%61th;ignored/info;param?query=value"));

        uri = HttpURI.build(uri)
            .scheme("https")
            .user(null)
            .authority("[::1]:8080")
            .decodedPath("/some encoded/evening")
            .param("id=12345")
            .query(null)
            .asImmutable();

        assertThat(uri.getScheme(), is("https"));
        assertThat(uri.getUser(), nullValue());
        assertThat(uri.getHost(), is("[::1]"));
        assertThat(uri.getPort(), is(8080));
        assertThat(uri.getPath(), is("/some%20encoded/evening;id=12345"));
        assertThat(uri.getDecodedPath(), is("/some encoded/evening"));
        assertThat(uri.getParam(), is("id=12345"));
        assertThat(uri.getQuery(), nullValue());
        assertThat(uri.getAuthority(), is("[::1]:8080"));
        assertThat(uri.toString(), is("https://[::1]:8080/some%20encoded/evening;id=12345"));
    }

    @Test
    public void testExample()
    {
        HttpURI uri = HttpURI.from("http://user:password@host:8888/ignored/../p%61th;ignored/info;param?query=value#fragment");

        assertThat(uri.getScheme(), is("http"));
        assertThat(uri.getUser(), is("user:password"));
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(8888));
        assertThat(uri.getPath(), is("/ignored/../p%61th;ignored/info;param"));
        assertThat(uri.getDecodedPath(), is("/path/info"));
        assertThat(uri.getParam(), is("param"));
        assertThat(uri.getQuery(), is("query=value"));
        assertThat(uri.getFragment(), is("fragment"));
        assertThat(uri.getAuthority(), is("host:8888"));
    }

    @Test
    public void testInvalidAddress()
    {
        assertInvalidURI("http://[ffff::1:8080/", "Invalid URL; no closing ']' -- should throw exception");
        assertInvalidURI("**", "only '*', not '**'");
        assertInvalidURI("*/", "only '*', not '*/'");
    }

    private void assertInvalidURI(String invalidURI, String message)
    {
        try
        {
            HttpURI.build(invalidURI);
            fail(message);
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(true);
        }
    }

    @Test
    public void testParse()
    {
        HttpURI.Mutable builder = HttpURI.build();
        HttpURI uri;

        builder.uri("*");
        uri = builder.asImmutable();
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("*"));

        builder.uri("/foo/bar");
        uri = builder.asImmutable();
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("/foo/bar"));

        builder.uri("//foo/bar");
        uri = builder.asImmutable();
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));

        builder.uri("http://foo/bar");
        uri = builder.asImmutable();
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));

        // We do allow nulls if not encoded.  This can be used for testing 2nd line of defence.
        builder.uri("http://fo\000/bar");
        uri = builder.asImmutable();
        assertThat(uri.getHost(), is("fo\000"));
        assertThat(uri.getPath(), is("/bar"));
    }

    @Test
    public void testParseRequestTarget()
    {
        HttpURI uri;

        uri = HttpURI.from("GET", "*");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("*"));

        uri = HttpURI.from("GET", "/foo/bar");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("/foo/bar"));

        uri = HttpURI.from("GET", "//foo/bar");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("//foo/bar"));

        uri = HttpURI.from("GET", "http://foo/bar");
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));
    }

    @Test
    public void testCONNECT()
    {
        HttpURI uri;

        uri = HttpURI.from("CONNECT", "host:80");
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(80));
        assertThat(uri.getPath(), nullValue());

        uri = HttpURI.from("CONNECT", "host");
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(-1));
        assertThat(uri.getPath(), nullValue());

        uri = HttpURI.from("CONNECT", "192.168.0.1:8080");
        assertThat(uri.getHost(), is("192.168.0.1"));
        assertThat(uri.getPort(), is(8080));
        assertThat(uri.getPath(), nullValue());

        uri = HttpURI.from("CONNECT", "[::1]:8080");
        assertThat(uri.getHost(), is("[::1]"));
        assertThat(uri.getPort(), is(8080));
        assertThat(uri.getPath(), nullValue());
    }

    @Test
    public void testAt()
    {
        HttpURI uri = HttpURI.from("/@foo/bar");
        assertEquals("/@foo/bar", uri.getPath());
    }

    @Test
    public void testParams()
    {
        HttpURI uri = HttpURI.from("/foo/bar");
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertNull(uri.getParam());

        uri = HttpURI.from("/foo/bar;jsessionid=12345");
        assertEquals("/foo/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = HttpURI.from("/foo;abc=123/bar;jsessionid=12345");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = HttpURI.from("/foo;abc=123/bar;jsessionid=12345?name=value");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = HttpURI.from("/foo;abc=123/bar;jsessionid=12345#target");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());
    }

    @Test
    public void testMutableURIBuilder()
    {
        HttpURI.Mutable builder = HttpURI.build("/foo/bar");
        HttpURI uri = builder.asImmutable();
        assertEquals("/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.scheme("http").asImmutable();
        assertEquals("http:/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.authority("host", 0).asImmutable();
        assertEquals("http://host/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.authority("host", 8888).asImmutable();
        assertEquals("http://host:8888/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertNull(uri.getQuery());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2?name=value").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?name=value", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals("name=value", uri.getQuery());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertNull(uri.getQuery());

        uri = builder.query("other=123456").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?other=123456", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals("other=123456", uri.getQuery());
    }

    @Test
    public void testSchemeAndOrAuthority()
    {
        HttpURI.Mutable builder = HttpURI.build("/path/info");
        HttpURI uri = builder.asImmutable();
        assertEquals("/path/info", uri.toString());

        uri = builder.authority("host", 0).asImmutable();
        assertEquals("//host/path/info", uri.toString());

        uri = builder.authority("host", 8888).asImmutable();
        assertEquals("//host:8888/path/info", uri.toString());

        uri = builder.scheme("http").asImmutable();
        assertEquals("http://host:8888/path/info", uri.toString());

        uri = builder.authority(null, 0).asImmutable();
        assertEquals("http:/path/info", uri.toString());
    }

    @Test
    public void testBasicAuthCredentials()
    {
        HttpURI uri = HttpURI.from("http://user:password@example.com:8888/blah");
        assertEquals("http://user:password@example.com:8888/blah", uri.toString());
        assertEquals(uri.getAuthority(), "example.com:8888");
        assertEquals(uri.getUser(), "user:password");
    }

    @Test
    public void testCanonicalDecoded()
    {
        HttpURI uri = HttpURI.from("/path/.info");
        assertEquals("/path/.info", uri.getDecodedPath());

        uri = HttpURI.from("/path/./info");
        assertEquals("/path/info", uri.getDecodedPath());

        uri = HttpURI.from("/path/../info");
        assertEquals("/info", uri.getDecodedPath());

        uri = HttpURI.from("/./path/info.");
        assertEquals("/path/info.", uri.getDecodedPath());

        uri = HttpURI.from("./path/info/.");
        assertEquals("path/info/", uri.getDecodedPath());

        uri = HttpURI.from("http://host/path/.info");
        assertEquals("/path/.info", uri.getDecodedPath());

        uri = HttpURI.from("http://host/path/./info");
        assertEquals("/path/info", uri.getDecodedPath());

        uri = HttpURI.from("http://host/path/../info");
        assertEquals("/info", uri.getDecodedPath());

        uri = HttpURI.from("http://host/./path/info.");
        assertEquals("/path/info.", uri.getDecodedPath());

        uri = HttpURI.from("http:./path/info/.");
        assertEquals("path/info/", uri.getDecodedPath());
    }

    public static Stream<Arguments> decodePathTests()
    {
        return Arrays.stream(new Object[][]
            {
                // Simple path example
                {"http://host/path/info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"//host/path/info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"/path/info", "/path/info", EnumSet.noneOf(Violation.class)},

                // Scheme & host containing unusual valid characters
                {"ht..tp://host/path/info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"ht1.2+..-3.4tp://127.0.0.1:8080/path/info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"http://h%2est/path/info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"http://h..est/path/info", "/path/info", EnumSet.noneOf(Violation.class)},

                // legal non ambiguous relative paths
                {"http://host/../path/info", null, EnumSet.noneOf(Violation.class)},
                {"http://host/path/../info", "/info", EnumSet.noneOf(Violation.class)},
                {"http://host/path/./info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"//host/path/../info", "/info", EnumSet.noneOf(Violation.class)},
                {"//host/path/./info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"/path/../info", "/info", EnumSet.noneOf(Violation.class)},
                {"/path/./info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"path/../info", "info", EnumSet.noneOf(Violation.class)},
                {"path/./info", "path/info", EnumSet.noneOf(Violation.class)},

                // encoded paths
                {"/f%6f%6F/bar", "/foo/bar", EnumSet.noneOf(Violation.class)},
                {"/f%u006f%u006F/bar", "/foo/bar", EnumSet.of(Violation.UTF16_ENCODINGS)},
                {"/f%u0001%u0001/bar", "/f\001\001/bar", EnumSet.of(Violation.UTF16_ENCODINGS)},
                {"/foo/%u20AC/bar", "/foo/\u20AC/bar", EnumSet.of(Violation.UTF16_ENCODINGS)},

                // illegal paths
                {"//host/../path/info", null, EnumSet.noneOf(Violation.class)},
                {"/../path/info", null, EnumSet.noneOf(Violation.class)},
                {"../path/info", null, EnumSet.noneOf(Violation.class)},
                {"/path/%XX/info", null, EnumSet.noneOf(Violation.class)},
                {"/path/%2/F/info", null, EnumSet.noneOf(Violation.class)},
                {"/path/%/info", null, EnumSet.noneOf(Violation.class)},
                {"/path/%u000X/info", null, EnumSet.noneOf(Violation.class)},
                {"/path/Fo%u0000/info", null, EnumSet.noneOf(Violation.class)},
                {"/path/Fo%00/info", null, EnumSet.noneOf(Violation.class)},
                {"/path/Foo/info%u0000", null, EnumSet.noneOf(Violation.class)},
                {"/path/Foo/info%00", null, EnumSet.noneOf(Violation.class)},
                {"/path/%U20AC", null, EnumSet.noneOf(Violation.class)},
                {"%2e%2e/info", null, EnumSet.noneOf(Violation.class)},
                {"%u002e%u002e/info", null, EnumSet.noneOf(Violation.class)},
                {"%2e%2e;/info", null, EnumSet.noneOf(Violation.class)},
                {"%u002e%u002e;/info", null, EnumSet.noneOf(Violation.class)},
                {"%2e.", null, EnumSet.noneOf(Violation.class)},
                {"%u002e.", null, EnumSet.noneOf(Violation.class)},
                {".%2e", null, EnumSet.noneOf(Violation.class)},
                {".%u002e", null, EnumSet.noneOf(Violation.class)},
                {"%2e%2e", null, EnumSet.noneOf(Violation.class)},
                {"%u002e%u002e", null, EnumSet.noneOf(Violation.class)},
                {"%2e%u002e", null, EnumSet.noneOf(Violation.class)},
                {"%u002e%2e", null, EnumSet.noneOf(Violation.class)},
                {"..;/info", null, EnumSet.noneOf(Violation.class)},
                {"..;param/info", null, EnumSet.noneOf(Violation.class)},

                // ambiguous dot encodings
                {"scheme://host/path/%2e/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"scheme:/path/%2e/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2e/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"path/%2e/info/", "path/info/", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2e%2e/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2e%2e;/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2e%2e;param/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2e%2e;param;other/info;other", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"%2e/info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"%u002e/info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.UTF16_ENCODINGS)},

                {"%2e", "", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"%u002e", "", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.UTF16_ENCODINGS)},

                // empty segment treated as ambiguous
                {"/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//../bar", "/foo/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo///../../../bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo/./../bar", "/bar", EnumSet.noneOf(Violation.class)},
                {"/foo//./bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"foo/bar", "foo/bar", EnumSet.noneOf(Violation.class)},
                {"foo;/bar", "foo/bar", EnumSet.noneOf(Violation.class)},
                {";/bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {";?n=v", "", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"?n=v", "", EnumSet.noneOf(Violation.class)},
                {"#n=v", "", EnumSet.noneOf(Violation.class)},
                {"", "", EnumSet.noneOf(Violation.class)},
                {"http:/foo", "/foo", EnumSet.noneOf(Violation.class)},

                // ambiguous parameter inclusions
                {"/path/.;/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/.;param/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/..;/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/..;param/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {".;/info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {".;param/info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},

                // ambiguous segment separators
                {"/path/%2f/info", "/path///info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"%2f/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"%2F/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"/path/%2f../info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},

                // ambiguous encoding
                {"/path/%25/info", "/path/%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},
                {"/path/%u0025/info", "/path/%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING, Violation.UTF16_ENCODINGS)},
                {"%25/info", "%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},
                {"/path/%25../info", "/path/%../info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},
                {"/path/%u0025../info", "/path/%../info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING, Violation.UTF16_ENCODINGS)},

                // combinations
                {"/path/%2f/..;/info", "/path//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%u002f/..;/info", "/path//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.UTF16_ENCODINGS)},
                {"/path/%2f/..;/%2e/info", "/path//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.AMBIGUOUS_PATH_SEGMENT)},

                // Non ascii characters
                // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
                {"http://localhost:9000/x\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/x\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", EnumSet.noneOf(Violation.class)},
                {"http://localhost:9000/\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", EnumSet.noneOf(Violation.class)},
                // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck
            }).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("decodePathTests")
    public void testDecodedPath(String input, String decodedPath, EnumSet<Violation> expected)
    {
        try
        {
            HttpURI uri = HttpURI.from(input);
            assertThat(uri.getDecodedPath(), is(decodedPath));
            EnumSet<Violation> ambiguous = EnumSet.copyOf(expected);
            ambiguous.retainAll(EnumSet.complementOf(EnumSet.of(Violation.UTF16_ENCODINGS)));

            assertThat(uri.isAmbiguous(), is(!ambiguous.isEmpty()));
            assertThat(uri.hasAmbiguousSegment(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_SEGMENT)));
            assertThat(uri.hasAmbiguousSeparator(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_SEPARATOR)));
            assertThat(uri.hasAmbiguousParameter(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_PARAMETER)));
            assertThat(uri.hasAmbiguousEncoding(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_ENCODING)));

            assertThat(uri.hasUtf16Encoding(), is(expected.contains(Violation.UTF16_ENCODINGS)));
        }
        catch (Exception e)
        {
            if (decodedPath != null)
                e.printStackTrace();
            assertThat(decodedPath, nullValue());
        }
    }

    public static Stream<Arguments> testPathQueryTests()
    {
        return Arrays.stream(new Object[][]
            {
                // Simple path example
                {"/path/info", "/path/info", EnumSet.noneOf(Violation.class)},

                // legal non ambiguous relative paths
                {"/path/../info", "/info", EnumSet.noneOf(Violation.class)},
                {"/path/./info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"path/../info", "info", EnumSet.noneOf(Violation.class)},
                {"path/./info", "path/info", EnumSet.noneOf(Violation.class)},

                // illegal paths
                {"/../path/info", null, null},
                {"../path/info", null, null},
                {"/path/%XX/info", null, null},
                {"/path/%2/F/info", null, null},
                {"%2e%2e/info", null, null},
                {"%2e%2e;/info", null, null},
                {"%2e.", null, null},
                {".%2e", null, null},
                {"%2e%2e", null, null},
                {"..;/info", null, null},
                {"..;param/info", null, null},

                // ambiguous dot encodings
                {"/path/%2e/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"path/%2e/info/", "path/info/", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2e%2e/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2e%2e;/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2e%2e;param/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2e%2e;param;other/info;other", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"%2e/info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"%2e", "", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},

                // empty segment treated as ambiguous
                {"/", "/", EnumSet.noneOf(Violation.class)},
                {"/#", "/", EnumSet.noneOf(Violation.class)},
                {"/path", "/path", EnumSet.noneOf(Violation.class)},
                {"/path/", "/path/", EnumSet.noneOf(Violation.class)},
                {"//", "//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//", "/foo//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"//foo/bar", "//foo/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo?bar", "/foo", EnumSet.noneOf(Violation.class)},
                {"/foo#bar", "/foo", EnumSet.noneOf(Violation.class)},
                {"/foo;bar", "/foo", EnumSet.noneOf(Violation.class)},
                {"/foo/?bar", "/foo/", EnumSet.noneOf(Violation.class)},
                {"/foo/#bar", "/foo/", EnumSet.noneOf(Violation.class)},
                {"/foo/;param", "/foo/", EnumSet.noneOf(Violation.class)},
                {"/foo/;param/bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//bar//", "/foo//bar//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"//foo//bar//", "//foo//bar//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//../bar", "/foo/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo///../../../bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo/./../bar", "/bar", EnumSet.noneOf(Violation.class)},
                {"/foo//./bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"foo/bar", "foo/bar", EnumSet.noneOf(Violation.class)},
                {"foo;/bar", "foo/bar", EnumSet.noneOf(Violation.class)},
                {";/bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {";?n=v", "", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"?n=v", "", EnumSet.noneOf(Violation.class)},
                {"#n=v", "", EnumSet.noneOf(Violation.class)},
                {"", "", EnumSet.noneOf(Violation.class)},

                // ambiguous parameter inclusions
                {"/path/.;/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/.;param/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/..;/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/..;param/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {".;/info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {".;param/info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},

                // ambiguous segment separators
                {"/path/%2f/info", "/path///info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"%2f/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"%2F/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"/path/%2f../info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},

                // ambiguous encoding
                {"/path/%25/info", "/path/%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},
                {"%25/info", "%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},
                {"/path/%25../info", "/path/%../info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},

                // combinations
                {"/path/%2f/..;/info", "/path//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2f/..;/%2e/info", "/path//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2f/%25/..;/%2e//info", "/path////info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_ENCODING, Violation.AMBIGUOUS_EMPTY_SEGMENT)},
            }).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("testPathQueryTests")
    public void testPathQuery(String input, String decodedPath, EnumSet<Violation> expected)
    {
        // If expected is null then it is a bad URI and should throw.
        if (expected == null)
        {
            assertThrows(Throwable.class, () -> HttpURI.build().pathQuery(input));
            return;
        }

        HttpURI uri = HttpURI.build().pathQuery(input);
        assertThat(uri.getDecodedPath(), is(decodedPath));
        assertThat(uri.isAmbiguous(), is(!expected.isEmpty()));
        assertThat(uri.hasAmbiguousEmptySegment(), is(expected.contains(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        assertThat(uri.hasAmbiguousSegment(), is(expected.contains(Violation.AMBIGUOUS_PATH_SEGMENT)));
        assertThat(uri.hasAmbiguousSeparator(), is(expected.contains(Violation.AMBIGUOUS_PATH_SEPARATOR)));
        assertThat(uri.hasAmbiguousParameter(), is(expected.contains(Violation.AMBIGUOUS_PATH_PARAMETER)));
        assertThat(uri.hasAmbiguousEncoding(), is(expected.contains(Violation.AMBIGUOUS_PATH_ENCODING)));
    }

    public static Stream<Arguments> parseData()
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
    @MethodSource("parseData")
    public void testParseString(String input, String scheme, String host, Integer port, String path, String param, String query, String fragment)
    {
        HttpURI httpUri = HttpURI.from(input);

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
    @MethodSource("parseData")
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
        assumeTrue(javaUri != null, "Skipping, not a valid input URI: " + input);

        HttpURI httpUri = HttpURI.from(javaUri);

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
    @MethodSource("parseData")
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

        HttpURI httpUri = HttpURI.from(javaUri);

        assertThat("[" + input + "] .scheme", httpUri.getScheme(), is(javaUri.getScheme()));
        assertThat("[" + input + "] .host", httpUri.getHost(), is(javaUri.getHost()));
        assertThat("[" + input + "] .port", httpUri.getPort(), is(javaUri.getPort()));
        assertThat("[" + input + "] .path", httpUri.getPath(), is(javaUri.getRawPath()));
        // Not Relevant for java.net.URI -- assertThat("["+input+"] .param", httpUri.getParam(), is(param));
        assertThat("[" + input + "] .query", httpUri.getQuery(), is(javaUri.getRawQuery()));
        assertThat("[" + input + "] .fragment", httpUri.getFragment(), is(javaUri.getFragment()));
        assertThat("[" + input + "] .toString", httpUri.toString(), is(javaUri.toASCIIString()));
    }

    public static Stream<Arguments> queryData()
    {
        return Stream.of(
            new String[]{"/path?p=%U20AC", "p=%U20AC"},
            new String[]{"/path?p=%u20AC", "p=%u20AC"}
        ).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("queryData")
    public void testEncodedQuery(String input, String expectedQuery)
    {
        HttpURI httpURI = HttpURI.build(input);
        assertThat("[" + input + "] .query", httpURI.getQuery(), is(expectedQuery));
    }

    @Test
    public void testRelativePathWithAuthority()
    {
        assertThrows(IllegalArgumentException.class, () -> HttpURI.build()
            .authority("host")
            .path("path"));
        assertThrows(IllegalArgumentException.class, () -> HttpURI.build()
            .authority("host", 8080)
            .path(";p=v/url"));
        assertThrows(IllegalArgumentException.class, () -> HttpURI.build()
            .host("host")
            .path(";"));

        assertThrows(IllegalArgumentException.class, () -> HttpURI.build()
            .path("path")
            .authority("host"));
        assertThrows(IllegalArgumentException.class, () -> HttpURI.build()
            .path(";p=v/url")
            .authority("host", 8080));
        assertThrows(IllegalArgumentException.class, () -> HttpURI.build()
            .path(";")
            .host("host"));

        HttpURI.Mutable uri = HttpURI.build()
            .path("*")
            .authority("host");
        assertEquals("//host*", uri.asString());
        uri = HttpURI.build()
            .authority("host")
            .path("*");
        assertEquals("//host*", uri.asString());

        uri = HttpURI.build()
            .path("")
            .authority("host");
        assertEquals("//host", uri.asString());
        uri = HttpURI.build()
            .authority("host")
            .path("");
        assertEquals("//host", uri.asString());
    }
}
