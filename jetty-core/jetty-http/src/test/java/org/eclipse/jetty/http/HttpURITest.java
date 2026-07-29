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
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.http.UriCompliance.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            .fragment("fragment")
            .asImmutable();

        assertThat(uri.getScheme(), is("http"));
        assertThat(uri.getUser(), is("user:password"));
        assertTrue(uri.hasViolation(Violation.USER_INFO));
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(8888));
        assertThat(uri.getPath(), is("/ignored/../p%61th;ignored/info;param"));
        assertThat(uri.getCanonicalPath(), is("/path/info"));
        assertThat(uri.getParam(), is("param"));
        assertThat(uri.getQuery(), is("query=value"));
        assertThat(uri.getFragment(), is("fragment"));
        assertThat(uri.getAuthority(), is("host:8888"));
        assertThat(uri.toString(), is("http://user:password@host:8888/ignored/../p%61th;ignored/info;param?query=value#fragment"));
        assertThat(uri.toURI().toString(), is("http://user:password@host:8888/ignored/../p%61th;ignored/info;param?query=value#fragment"));

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
        assertFalse(uri.hasViolation(Violation.USER_INFO));
        assertThat(uri.getHost(), is("[::1]"));
        assertThat(uri.getPort(), is(8080));
        assertThat(uri.getPath(), is("/some%20encoded/evening;id=12345"));
        assertThat(uri.getCanonicalPath(), is("/some%20encoded/evening"));
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
        assertTrue(uri.hasViolation(Violation.USER_INFO));
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(8888));
        assertThat(uri.getPath(), is("/ignored/../p%61th;ignored/info;param"));
        assertThat(uri.getCanonicalPath(), is("/path/info"));
        assertThat(uri.getParam(), is("param"));
        assertThat(uri.getQuery(), is("query=value"));
        assertThat(uri.getFragment(), is("fragment"));
        assertThat(uri.getAuthority(), is("host:8888"));
    }

    public static Stream<Arguments> invalidURICases()
    {
        return Stream.of(
            Arguments.of("http://[ffff::1:8080/", "Invalid URL; no closing ']' -- should throw exception"),
            Arguments.of("**", "only '*', not '**'"),
            Arguments.of("*/", "only '*', not '*/'"),
            Arguments.of("http://fo\000/bar", "We do not allow nulls in raw, unencoded, form"),
            Arguments.of("http://foo:8080./bar", "Invalid port designation")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidURICases")
    public void testInvalidURIBuild(String invalidURI, String message)
    {
        assertThrows(IllegalArgumentException.class, () -> HttpURI.build(invalidURI), message);
    }

    @ParameterizedTest
    @MethodSource("invalidURICases")
    public void testInvalidURIBuilderUri(String invalidURI, String message)
    {
        assertThrows(IllegalArgumentException.class, () -> HttpURI.build().uri(invalidURI), message);
    }

    public static Stream<Arguments> parseCases()
    {
        return Stream.of(
            Arguments.of("*", null, "*"),
            Arguments.of("/foo/bar", null, "/foo/bar"),
            Arguments.of("//foo/bar", "foo", "/bar"),
            Arguments.of("http://foo/bar", "foo", "/bar")
        );
    }

    @ParameterizedTest
    @MethodSource("parseCases")
    public void testBuilderUri(String input, String expectedHost, String expectedPath)
    {
        HttpURI.Mutable builder = HttpURI.build();
        HttpURI uri;

        builder.uri(input);
        uri = builder.asImmutable();
        assertThat(uri.getHost(), is(expectedHost));
        assertThat(uri.getPath(), is(expectedPath));
    }

    public static Stream<Arguments> fromRequestTargetCases()
    {
        return Stream.of(
            Arguments.of("GET", "*", null, -1, "*", "*"),
            Arguments.of("GET", "/foo/bar", null, -1, "/foo/bar", "/foo/bar"),
            Arguments.of("GET", "//foo/bar", null, -1, "//foo/bar", "//foo/bar"),
            Arguments.of("GET", "http://foo/bar", "foo", -1, "/bar", "http://foo/bar"),
            // CONNECT variants
            Arguments.of("CONNECT", "host:80", "host", 80, null, "//host:80"),
            Arguments.of("CONNECT", "host", "host", -1, null, "//host"),
            Arguments.of("CONNECT", "192.168.0.1:8080", "192.168.0.1", 8080, null, "//192.168.0.1:8080"),
            Arguments.of("CONNECT", "[::1]:443", "[::1]", 443, null, "//[::1]:443")
        );
    }

    @ParameterizedTest
    @MethodSource("fromRequestTargetCases")
    public void testParseRequestTarget(String inputMethod, String inputURI, String expectedHost, int expectedPort, String expectedPath, String expectedToString)
    {
        HttpURI uri;

        uri = HttpURI.from(inputMethod, inputURI);
        assertThat("uri.host", uri.getHost(), is(expectedHost));
        assertThat("uri.path", uri.getPath(), is(expectedPath));
        assertThat("uri.port", uri.getPort(), is(expectedPort));
        assertThat("uri.toString", uri.toString(), is(expectedToString));
    }

    @Test
    public void testAt()
    {
        HttpURI uri = HttpURI.from("/@foo/bar");
        assertEquals("/@foo/bar", uri.getPath());
    }

    /**
     * Test of an HttpURI of just a "/".
     * The {@link HttpURI#from(String)} is used by HttpServletResponse.sendRedirect(String).
     */
    @Test
    public void testFromSlash()
    {
        HttpURI uri = HttpURI.from("/");
        assertThat("has no violations", uri.getViolations(), is(empty()));
        assertNull(uri.getScheme());
        assertNull(uri.getAuthority());
        assertEquals("/", uri.getPath());
    }

    public static Stream<Arguments> fromUriPathParamCases()
    {
        return Stream.of(
            Arguments.of("/foo/bar", "/foo/bar", "/foo/bar", null),
            Arguments.of("/foo/bar;jsessionid=12345", "/foo/bar;jsessionid=12345", "/foo/bar", "jsessionid=12345"),
            Arguments.of("/foo;abc=123/bar;jsessionid=12345", "/foo;abc=123/bar;jsessionid=12345", "/foo/bar", "jsessionid=12345"),
            Arguments.of("/foo;abc=123/bar;jsessionid=12345?name=value", "/foo;abc=123/bar;jsessionid=12345", "/foo/bar", "jsessionid=12345"),
            Arguments.of("/foo;abc=123/bar;jsessionid=12345#target", "/foo;abc=123/bar;jsessionid=12345", "/foo/bar", "jsessionid=12345")
        );
    }

    @ParameterizedTest
    @MethodSource("fromUriPathParamCases")
    public void testParams(String inputUri, String expectedPath, String expectedCanonicalPath, String expectedParam)
    {
        HttpURI uri = HttpURI.from(inputUri);
        assertThat("uri.path", uri.getPath(), is(expectedPath));
        assertThat("uri.canonicalPath", uri.getCanonicalPath(), is(expectedCanonicalPath));
        assertThat("uri.param", uri.getParam(), is(expectedParam));
    }

    /**
     * Test of the HttpURI.Mutable builder, with an ever-increasing set
     * of URI features added and tested.
     */
    @Test
    public void testMutableURIBuilder()
    {
        HttpURI.Mutable builder = HttpURI.build("/foo/bar");
        HttpURI uri = builder.asImmutable();
        assertEquals("/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getCanonicalPath());

        uri = builder.scheme("http").asImmutable();
        assertEquals("http:/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getCanonicalPath());

        uri = builder.authority("host", 0).asImmutable();
        assertEquals("http://host/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getCanonicalPath());

        uri = builder.authority("host", 8888).asImmutable();
        assertEquals("http://host:8888/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getCanonicalPath());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getCanonicalPath());
        assertEquals("p1;p2", uri.getParam());
        assertNull(uri.getQuery());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2?name=value").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?name=value", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getCanonicalPath());
        assertEquals("p1;p2", uri.getParam());
        assertEquals("name=value", uri.getQuery());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getCanonicalPath());
        assertEquals("p1;p2", uri.getParam());
        assertNull(uri.getQuery());

        uri = builder.query("other=123456").asImmutable();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?other=123456", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getCanonicalPath());
        assertEquals("p1;p2", uri.getParam());
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
        assertEquals("example.com:8888", uri.getAuthority());
        assertEquals("user:password", uri.getUser());
        assertTrue(uri.hasViolation(Violation.USER_INFO));
    }

    public static Stream<Arguments> fromCanonicalDecodedCases()
    {
        return Stream.of(
            Arguments.of("/path/.info", "/path/.info"),
            Arguments.of("/path/./info", "/path/info"),
            Arguments.of("/path/../info", "/info"),
            Arguments.of("/path;/./info", "/path/info"),
            Arguments.of("/path;/../info", "/info"),
            Arguments.of("/./path/info.", "/path/info."),
            Arguments.of("./path/info/.", "path/info/"),
            Arguments.of("http://host/path/.info", "/path/.info"),
            Arguments.of("http://host/path/.info", "/path/.info"),
            Arguments.of("http://host/path/./info", "/path/info"),
            Arguments.of("http://host/path/../info", "/info"),
            Arguments.of("http://host/path;/./info", "/path/info"),
            Arguments.of("http://host/path;/../info", "/info"),
            Arguments.of("http://host/./path/info.", "/path/info."),
            Arguments.of("http://host./path/info/.", "/path/info/"),
            Arguments.of("http://host:8080/path/.info", "/path/.info"),
            Arguments.of("http://host:8080/path/.info", "/path/.info"),
            Arguments.of("http://host:8080/path/./info", "/path/info"),
            Arguments.of("http://host:8080/path/../info", "/info"),
            Arguments.of("http://host:8080/path;/./info", "/path/info"),
            Arguments.of("http://host:8080/path;/../info", "/info"),
            Arguments.of("http://host:8080/./path/info.", "/path/info."),
            Arguments.of("http:/path/.info", "/path/.info"),
            Arguments.of("http:/path/./info", "/path/info"),
            Arguments.of("http:/path/../info", "/info"),
            Arguments.of("http:/path;/./info", "/path/info"),
            Arguments.of("http:/path;/../info", "/info"),
            Arguments.of("http:/./path/info.", "/path/info."),
            Arguments.of("http:./path/info/.", "path/info/")
        );
    }

    @ParameterizedTest
    @MethodSource("fromCanonicalDecodedCases")
    public void testCanonicalDecoded(String inputUri, String expectedCanonicalPath)
    {
        HttpURI uri = HttpURI.from(inputUri);
        assertThat("uri.canonicalPath", uri.getCanonicalPath(), is(expectedCanonicalPath));
    }

    public static Stream<Arguments> decodePathTests()
    {
        List<Arguments> cases = new ArrayList<>();

        // Simple path example
        cases.add(Arguments.of("http://host/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("//host/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));

        // Scheme & host containing unusual valid characters
        cases.add(Arguments.of("ht..tp://host/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("ht1.2+..-3.4tp://127.0.0.1:8080/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("http://h%2est/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("http://h..est/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));

        // legal non ambiguous relative paths
        cases.add(Arguments.of("http://host/../path/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("http://host/path/../info", "/info", "/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("http://host/path/./info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("//host/path/../info", "/info", "/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("//host/path;/../info", "/info", "/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("//host/path/./info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("//host/path;/./info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/../info", "/info", "/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path;/../info", "/info", "/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/./info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path;/./info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("path/../info", "info", "info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("path;/../info", "info", "info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("path/./info", "path/info", "path/info", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("path;/./info", "path/info", "path/info", EnumSet.noneOf(Violation.class)));

        // encoded paths
        cases.add(Arguments.of("/f%6f%6F/bar", "/foo/bar", "/foo/bar", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/context/dir%3B/", "/context/dir%3B/", "/context/dir;/", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/f%u006f%u006F/bar", "/foo/bar", "/foo/bar", EnumSet.of(Violation.UTF16_ENCODINGS)));
        cases.add(Arguments.of("/f%u0001%u0001/bar", "/f%01%01/bar", "/f\001\001/bar", EnumSet.of(Violation.UTF16_ENCODINGS)));
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        cases.add(Arguments.of("/foo/%u20AC/bar", "/foo/\u20AC/bar", "/foo/\u20AC/bar", EnumSet.of(Violation.UTF16_ENCODINGS)));
        // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck

        // nfc encoded unicode path
        cases.add(Arguments.of("/dir/swedish-%C3%A5.txt", "/dir/swedish-å.txt", "/dir/swedish-å.txt", EnumSet.noneOf(Violation.class)));

        // nfd encoded unicode path
        cases.add(Arguments.of("/dir/swedish-a%CC%8A.txt", URLDecoder.decode("/dir/swedish-a%CC%8A.txt", UTF_8), URLDecoder.decode("/dir/swedish-a%CC%8A.txt", UTF_8), EnumSet.noneOf(Violation.class)));

        // illegal paths
        cases.add(Arguments.of("//host/../path/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/../path/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("../path/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/%XX/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/%2/F/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/%/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/%u000X/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/Fo%u0000/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/Fo%00/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/Foo/info%u0000", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/Foo/info%00", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/path/%U20AC", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%2e%2e/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%u002e%u002e/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%2e%2e;/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%u002e%u002e;/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%2e.", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%u002e.", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of(".%2e", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of(".%u002e", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%2e%2e", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%u002e%u002e", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%2e%u002e", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("%u002e%2e", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("..;/info", null, null, EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("..;param/info", null, null, EnumSet.noneOf(Violation.class)));

        // ambiguous dot encodings
        cases.add(Arguments.of("scheme://host/path/%2e/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)));
        cases.add(Arguments.of("scheme:/path/%2e/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)));
        cases.add(Arguments.of("/path/%2e/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)));
        cases.add(Arguments.of("path/%2e/info/", "path/info/", "path/info/", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)));
        cases.add(Arguments.of("/path/%2e%2e/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)));
        cases.add(Arguments.of("/path/%2e%2e;/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of("/path/%2e%2e;param/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of("/path/%2e%2e;param;other/info;other", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of("%2e/info", "info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)));
        cases.add(Arguments.of("%u002e/info", "info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.UTF16_ENCODINGS)));

        cases.add(Arguments.of("%2e", "", "", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)));
        cases.add(Arguments.of("%u002e", "", "", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.UTF16_ENCODINGS)));

        // empty segment treated as ambiguous
        cases.add(Arguments.of("/foo//bar", "/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        cases.add(Arguments.of("/foo//../bar", "/foo/bar", "/foo/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        cases.add(Arguments.of("/foo///../../../bar", "/bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        cases.add(Arguments.of("/foo/./../bar", "/bar", "/bar", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("/foo//./bar", "/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        cases.add(Arguments.of("foo/bar", "foo/bar", "foo/bar", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("foo;/bar", "foo/bar", "foo/bar", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of(";/bar", "/bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        cases.add(Arguments.of(";?n=v", "", "", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        cases.add(Arguments.of("?n=v", "", "", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("#n=v", "", "", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("", "", "", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("http:/foo", "/foo", "/foo", EnumSet.noneOf(Violation.class)));

        // ambiguous parameter inclusions
        cases.add(Arguments.of("/path/.;/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of("/path/.;param/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of("/path/..;/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of("/path/..;param/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of(".;/info", "info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of(".;param/info", "info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)));

        // ambiguous segment separators
        cases.add(Arguments.of("/path/%2f/info", "/path/%2F/info", "/path///info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)));
        cases.add(Arguments.of("%2f/info", "%2F/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)));
        cases.add(Arguments.of("%2F/info", "%2F/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)));
        cases.add(Arguments.of("/path/%2f../info", "/path/%2F../info", "/path//../info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)));

        // ambiguous encoding
        cases.add(Arguments.of("/path/%25/info", "/path/%25/info", "/path/%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)));
        cases.add(Arguments.of("/path/%2520/info", "/path/%2520/info", "/path/%20/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)));
        cases.add(Arguments.of("/path/%u0025/info", "/path/%25/info", "/path/%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING, Violation.UTF16_ENCODINGS)));
        cases.add(Arguments.of("%25/info", "%25/info", "%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)));
        cases.add(Arguments.of("/path/%25../info", "/path/%25../info", "/path/%../info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)));
        cases.add(Arguments.of("/path/%u0025../info", "/path/%25../info", "/path/%../info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING, Violation.UTF16_ENCODINGS)));

        // bad utf8
        cases.add(Arguments.of("/path/%C0%AF/info", "/path/��/info", "/path/��/info", EnumSet.of(Violation.BAD_UTF8_ENCODING)));

        // combinations
        cases.add(Arguments.of("/path/%2f/..;/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER)));
        cases.add(Arguments.of("/path/%u002f/..;/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.UTF16_ENCODINGS)));
        cases.add(Arguments.of("/path/%2f/..;/%2e/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.AMBIGUOUS_PATH_SEGMENT)));

        // Non ascii characters
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        cases.add(Arguments.of("http://localhost:9000/x\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/x\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/x\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", EnumSet.noneOf(Violation.class)));
        cases.add(Arguments.of("http://localhost:9000/\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", EnumSet.noneOf(Violation.class)));
        // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck

        // An empty (null) authority
        cases.add(Arguments.of("http://", null, null, null));

        // Fragments
        cases.add(Arguments.of("http://host/path/info#fragment", "/path/info", "/path/info", EnumSet.of(Violation.FRAGMENT)));
        cases.add(Arguments.of("//host/path/info#frag/ment", "/path/info", "/path/info", EnumSet.of(Violation.FRAGMENT)));
        cases.add(Arguments.of("/path/info#fragment", "/path/info", "/path/info", EnumSet.of(Violation.FRAGMENT)));
        cases.add(Arguments.of("http://example.com#@fragmentnothost", "", "", EnumSet.of(Violation.FRAGMENT)));

        // Test various with authorities.
        List<String> authorities = List.of(
            "192.168.0.1",
            "192.168.0.1:8080",
            "[fdc7:2df6:7735:25ed:9e6b:ff:fec0:7d98]",
            "[fdc7:2df6:7735:25ed:9e6b:ff:fec0:7d98]:8080",
            "[::1]",
            "[::1]:8080"
        );

        for (String authority: authorities)
        {
            cases.add(Arguments.of("http://%s/path/info".formatted(authority), "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
            cases.add(Arguments.of("http://%s/path/../info".formatted(authority), "/info", "/info", EnumSet.noneOf(Violation.class)));
            cases.add(Arguments.of("http://%s/path/./info".formatted(authority), "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
            cases.add(Arguments.of("http://%s/;/path/info".formatted(authority), "//path/info", "//path/info", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
            cases.add(Arguments.of("http://%s/path;/info".formatted(authority), "/path/info", "/path/info", EnumSet.noneOf(Violation.class)));
            cases.add(Arguments.of("http://%s//path/info".formatted(authority), "//path/info", "//path/info", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
            cases.add(Arguments.of("http://%s//path/info/".formatted(authority), "//path/info/", "//path/info/", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
            cases.add(Arguments.of("http://%s/path//info".formatted(authority), "/path//info", "/path//info", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
            cases.add(Arguments.of("http://%s/path//info/".formatted(authority), "/path//info/", "/path//info/", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
            cases.add(Arguments.of("http://%s/path/info//".formatted(authority), "/path/info//", "/path/info//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        }

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("decodePathTests")
    public void testDecodedPath(String input, String expectedCanonicalPath, String expectedDecodedPath, EnumSet<Violation> expected)
    {
        try
        {
            HttpURI uri = HttpURI.from(input);
            assertThat("Canonical Path - " + input, uri.getCanonicalPath(), is(expectedCanonicalPath));
            assertThat("Decoded Path - " + input, uri.getDecodedPath(), is(expectedDecodedPath));

            EnumSet<Violation> ambiguous = EnumSet.copyOf(expected);
            ambiguous.retainAll(UriCompliance.AMBIGUOUS_VIOLATIONS);

            assertThat(input, uri.isAmbiguous(), is(!ambiguous.isEmpty()));
            assertThat(input, uri.hasAmbiguousSegment(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_SEGMENT)));
            assertThat(input, uri.hasAmbiguousSeparator(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_SEPARATOR)));
            assertThat(input, uri.hasAmbiguousParameter(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_PARAMETER)));
            assertThat(input, uri.hasAmbiguousEncoding(), is(ambiguous.contains(Violation.AMBIGUOUS_PATH_ENCODING)));

            assertThat(input, uri.hasUtf16Encoding(), is(expected.contains(Violation.UTF16_ENCODINGS)));
        }
        catch (Exception e)
        {
            if (expectedDecodedPath != null)
                fail("expected a decoded path, but we failed to parse", e);
        }
    }

    public static Stream<Arguments> testPathQueryTests()
    {
        return Arrays.stream(new Object[][]
            {
                // Simple path example
                {"/path/info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)},

                // legal non ambiguous relative paths
                {"/path/../info", "/info", "/info", EnumSet.noneOf(Violation.class)},
                {"/path/./info", "/path/info", "/path/info", EnumSet.noneOf(Violation.class)},
                {"path/../info", "info", "info", EnumSet.noneOf(Violation.class)},
                {"path/./info", "path/info", "path/info", EnumSet.noneOf(Violation.class)},

                // illegal paths
                {"/../path/info", null, null, null},
                {"../path/info", null, null, null},
                {"/path/%XX/info", null, null, null},
                {"/path/%2/F/info", null, null, null},
                {"%2e%2e/info", null, null, null},
                {"%2e%2e;/info", null, null, null},
                {"%2e.", null, null, null},
                {".%2e", null, null, null},
                {"%2e%2e", null, null, null},
                {"..;/info", null, null, null},
                {"..;param/info", null, null, null},

                // ambiguous dot encodings
                {"/path/%2e/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"path/%2e/info/", "path/info/", "path/info/", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2e%2e/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2e%2e;/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2e%2e;param/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2e%2e;param;other/info;other", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"%2e/info", "info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"%2e", "", "", EnumSet.of(Violation.AMBIGUOUS_PATH_SEGMENT)},

                // empty segment treated as ambiguous
                {"/", "/", "/", EnumSet.noneOf(Violation.class)},
                {"/#", "/", "/", EnumSet.noneOf(Violation.class)},
                {"/path", "/path", "/path", EnumSet.noneOf(Violation.class)},
                {"/path/", "/path/", "/path/", EnumSet.noneOf(Violation.class)},
                {"//", "//", "//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//", "/foo//", "/foo//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//bar", "/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"//foo/bar", "//foo/bar", "//foo/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo?bar", "/foo", "/foo", EnumSet.noneOf(Violation.class)},
                {"/foo#bar", "/foo", "/foo", EnumSet.noneOf(Violation.class)},
                {"/foo;bar", "/foo", "/foo", EnumSet.noneOf(Violation.class)},
                {"/foo/?bar", "/foo/", "/foo/", EnumSet.noneOf(Violation.class)},
                {"/foo/#bar", "/foo/", "/foo/", EnumSet.noneOf(Violation.class)},
                {"/foo/;param", "/foo/", "/foo/", EnumSet.noneOf(Violation.class)},
                {"/foo/;param/bar", "/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//bar", "/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//bar//", "/foo//bar//", "/foo//bar//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"//foo//bar//", "//foo//bar//", "//foo//bar//", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo//../bar", "/foo/bar", "/foo/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo///../../../bar", "/bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"/foo/./../bar", "/bar", "/bar", EnumSet.noneOf(Violation.class)},
                {"/foo//./bar", "/foo//bar", "/foo//bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"foo/bar", "foo/bar", "foo/bar", EnumSet.noneOf(Violation.class)},
                {"foo;/bar", "foo/bar", "foo/bar", EnumSet.noneOf(Violation.class)},
                {";/bar", "/bar", "/bar", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {";?n=v", "", "", EnumSet.of(Violation.AMBIGUOUS_EMPTY_SEGMENT)},
                {"?n=v", "", "", EnumSet.noneOf(Violation.class)},
                {"#n=v", "", "", EnumSet.noneOf(Violation.class)},
                {"", "", "", EnumSet.noneOf(Violation.class)},

                // ambiguous parameter inclusions
                {"/path/.;/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/.;param/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/..;/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/..;param/info", "/info", "/info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {".;/info", "info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},
                {".;param/info", "info", "info", EnumSet.of(Violation.AMBIGUOUS_PATH_PARAMETER)},

                // ambiguous segment separators
                {"/path/%2f/info", "/path/%2F/info", "/path///info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"%2f/info", "%2F/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"%2F/info", "%2F/info", "//info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},
                {"/path/%2f../info", "/path/%2F../info", "/path//../info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR)},

                // ambiguous encoding
                {"/path/%25/info", "/path/%25/info", "/path/%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},
                {"%25/info", "%25/info", "%/info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},
                {"/path/%25../info", "/path/%25../info", "/path/%../info", EnumSet.of(Violation.AMBIGUOUS_PATH_ENCODING)},

                // combinations
                {"/path/%2f/..;/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER)},
                {"/path/%2f/..;/%2e/info", "/path/info", "/path/info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.AMBIGUOUS_PATH_SEGMENT)},
                {"/path/%2f/%25/..;/%2e//info", "/path/%2F//info", "/path////info", EnumSet.of(Violation.AMBIGUOUS_PATH_SEPARATOR, Violation.AMBIGUOUS_PATH_PARAMETER, Violation.AMBIGUOUS_PATH_SEGMENT, Violation.AMBIGUOUS_PATH_ENCODING, Violation.AMBIGUOUS_EMPTY_SEGMENT)},
            }).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("testPathQueryTests")
    public void testBuilderPathQuery(String input, String canonicalPath, String decodedPath, EnumSet<Violation> expected)
    {
        // If expected is null then it is a bad URI and should throw.
        if (expected == null)
        {
            assertThrows(Throwable.class, () -> HttpURI.build().pathQuery(input));
            return;
        }

        HttpURI uri = HttpURI.build().pathQuery(input);
        assertThat(uri.getCanonicalPath(), is(canonicalPath));
        assertThat(uri.getDecodedPath(), is(decodedPath));
        assertThat(uri.isAmbiguous(), is(!expected.isEmpty()));
        assertThat(uri.hasAmbiguousEmptySegment(), is(expected.contains(Violation.AMBIGUOUS_EMPTY_SEGMENT)));
        assertThat(uri.hasAmbiguousSegment(), is(expected.contains(Violation.AMBIGUOUS_PATH_SEGMENT)));
        assertThat(uri.hasAmbiguousSeparator(), is(expected.contains(Violation.AMBIGUOUS_PATH_SEPARATOR)));
        assertThat(uri.hasAmbiguousParameter(), is(expected.contains(Violation.AMBIGUOUS_PATH_PARAMETER)));
        assertThat(uri.hasAmbiguousEncoding(), is(expected.contains(Violation.AMBIGUOUS_PATH_ENCODING)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/a%2Fb",
        "/a%2F",
        "/%2f",
        "/%2f/"
    })
    public void testAmbiguousViaBuilderPath(String input)
    {
        HttpURI uri = HttpURI.build().path(input);
        assertThat("has any violation", uri.hasViolations(), is(true));
        assertThat("is ambiguous", uri.isAmbiguous(), is(true));
    }

    public static Stream<Arguments> suspiciousPathCharacterData()
    {
        return Stream.of(
            // backslash
            Arguments.of("/a%5Cb"),
            Arguments.of("/a\\b"),
            Arguments.of("/foo/bar/zed/%5c"),
            Arguments.of("/foo/bar/..\\zed"),
            Arguments.of("/foo/bar/zed/%5C"),
            // TAB
            Arguments.of("/%09b"),
            Arguments.of("/a\tb"),
            Arguments.of("/%09"),
            // CR / LF
            Arguments.of("/%0A"),
            Arguments.of("/%0a"),
            Arguments.of("/%0D"),
            Arguments.of("/%0d"),
            Arguments.of("/a/\r/\n/b"),
            Arguments.of("/foo\r\nbar/"),
            Arguments.of("/%0d%0a")
        );
    }

    @ParameterizedTest
    @MethodSource("suspiciousPathCharacterData")
    public void testSuspiciousPathCharacterBuilderPath(String input)
    {
        HttpURI uri = HttpURI.build().path(input);
        assertThat(uri.hasViolations(), is(true));
        assertThat("has SUSPICIOUS_PATH_CHARACTERS violation", uri.hasViolation(Violation.SUSPICIOUS_PATH_CHARACTERS), is(true));
    }

    @ParameterizedTest
    @MethodSource("suspiciousPathCharacterData")
    public void testSuspiciousPathCharacterFromString(String input)
    {
        HttpURI uri = HttpURI.from(input);
        assertThat(uri.hasViolations(), is(true));
        assertThat("has SUSPICIOUS_PATH_CHARACTERS violation", uri.hasViolation(Violation.SUSPICIOUS_PATH_CHARACTERS), is(true));
    }

    public static Stream<Arguments> illegalPathCharacterData()
    {
        return Stream.of(
            // backslash
            Arguments.of("/a\\b"),
            Arguments.of("/a/..\\b"),
            // control character
            Arguments.of("/a\tb"),
            Arguments.of("/a\rb"),
            Arguments.of("/a\nb"),
            // Pipe / piping symbols
            Arguments.of("/a|b"),
            Arguments.of("/a<b"),
            Arguments.of("/a>b"),
            // space character
            Arguments.of("/a b"),
            // double-quotes
            Arguments.of("/a\"b")
        );
    }

    @ParameterizedTest
    @MethodSource("illegalPathCharacterData")
    public void testIllegalPathCharacterBuilderPath(String input)
    {
        HttpURI uri = HttpURI.build().path(input);
        assertThat(uri.hasViolations(), is(true));
        assertThat(uri.hasViolation(Violation.ILLEGAL_PATH_CHARACTERS), is(true));

        if (input.startsWith("/") && input.indexOf('/', 1) == -1)
        {
            // Also test without leading slash
            uri = HttpURI.from(input.substring(1));
            assertThat(uri.hasViolations(), is(true));
            assertThat(uri.hasViolation(Violation.ILLEGAL_PATH_CHARACTERS), is(true));

            uri = HttpURI.from(input.substring(1) + "/extra");
            assertThat(uri.hasViolations(), is(true));
            assertThat(uri.hasViolation(Violation.ILLEGAL_PATH_CHARACTERS), is(true));

            uri = HttpURI.from(input.substring(1) + "#fragment");
            assertThat(uri.hasViolations(), is(true));
            assertThat(uri.hasViolation(Violation.ILLEGAL_PATH_CHARACTERS), is(true));
        }
    }

    @ParameterizedTest
    @MethodSource("illegalPathCharacterData")
    public void testIllegalPathCharacterFromString(String input)
    {
        HttpURI uri = HttpURI.from(input);
        assertThat(uri.hasViolations(), is(true));
        assertThat(uri.hasViolation(Violation.ILLEGAL_PATH_CHARACTERS), is(true));
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

            // Empty Paths
            Arguments.of("//localhost", null, "localhost", null, "", null, null, null),
            Arguments.of("http://localhost", "http", "localhost", null, "", null, null, null),
            Arguments.of("http://localhost?x=y", "http", "localhost", null, "", null, "x=y", null),
            Arguments.of("http://localhost#frag", "http", "localhost", null, "", null, null, "frag"),
            Arguments.of("http://localhost:8080", "http", "localhost", "8080", "", null, null, null),
            Arguments.of("http://localhost:8080?x=y", "http", "localhost", "8080", "", null, "x=y", null),
            Arguments.of("http://localhost:8080#frag", "http", "localhost", "8080", "", null, null, "frag"),

            // Simple path with parameter
            Arguments.of("/;param", null, null, null, "/;param", "param", null, null),
            Arguments.of(";param", null, null, null, ";param", "param", null, null),

            // Simple path with query
            Arguments.of("/?x=y", null, null, null, "/", null, "x=y", null),
            Arguments.of("/?abc=test", null, null, null, "/", null, "abc=test", null),

            // Encoded delimiters
            Arguments.of("/path%2finfo%3fa=?query", null, null, null, "/path%2finfo%3fa=", null, "query", null),

            // Simple path with fragment
            Arguments.of("/#fragment", null, null, null, "/", null, null, "fragment"),

            // Interpreted as relative path of "*" (no host/port/scheme/query/fragment)
            Arguments.of("*", null, null, null, "*", null, null, null),

            // Path detection Tests (seen from JSP/JSTL and <c:url> use)
            Arguments.of("http://host:8080/path/info?q1=v1&q2=v2", "http", "host", "8080", "/path/info", null, "q1=v1&q2=v2", null),
            Arguments.of("/path/info?q1=v1&q2=v2", null, null, null, "/path/info", null, "q1=v1&q2=v2", null),
            Arguments.of("/info?q1=v1&q2=v2", null, null, null, "/info", null, "q1=v1&q2=v2", null),
            Arguments.of("info?q1=v1&q2=v2", null, null, null, "info", null, "q1=v1&q2=v2", null),
            Arguments.of("info;q1=v1?q2=v2", null, null, null, "info;q1=v1", "q1=v1", "q2=v2", null),

            // Path-less, query only (seen from JSP/JSTL and <c:url> use)
            Arguments.of("?q1=v1&q2=v2", null, null, null, "", null, "q1=v1&q2=v2", null),

            // -- IPv4 --

            // Simple IPv4 host with port (default path)
            Arguments.of("http://192.0.0.1:8080/", "http", "192.0.0.1", "8080", "/", null, null, null),

            // Simple IPv4 host with port (no path)
            Arguments.of("http://192.0.0.1:8080", "http", "192.0.0.1", "8080", "", null, null, null),

            // Simple IPv4 host (default path)
            Arguments.of("http://192.0.0.1/", "http", "192.0.0.1", null, "/", null, null, null),

            // Simple IPv4 host (no path)
            Arguments.of("http://192.0.0.1", "http", "192.0.0.1", null, "", null, null, null),

            // -- IPv6 --

            // Simple IPv6 host with port (default path)
            Arguments.of("http://[2001:db8::1]:8080/", "http", "[2001:db8::1]", "8080", "/", null, null, null),

            // Simple IPv6 host with port (no path)
            Arguments.of("http://[2001:db8::1]:8080", "http", "[2001:db8::1]", "8080", "", null, null, null),

            // IPv6 userinfo + host with port (default path)
            Arguments.of("http://user@[2001:db8::1]:8080/", "http", "[2001:db8::1]", "8080", "/", null, null, null),

            // IPv6 userinfo + host with port (no path)
            Arguments.of("http://user@[2001:db8::1]:8080", "http", "[2001:db8::1]", "8080", "", null, null, null),

            // Simple IPv6 host no port (default path)
            Arguments.of("http://[2001:db8::1]/", "http", "[2001:db8::1]", null, "/", null, null, null),
            Arguments.of("http://[0:0:0:0:0:ffff:127.0.0.1]/", "http", "[0:0:0:0:0:ffff:127.0.0.1]", null, "/", null, null, null),
            Arguments.of("http://[::ffff:127.0.0.1]/", "http", "[::ffff:127.0.0.1]", null, "/", null, null, null),

            // Simple IPv6 host no port (no path)
            Arguments.of("http://[2001:db8::1]", "http", "[2001:db8::1]", null, "", null, null, null),
            Arguments.of("http://[0:0:0:0:0:ffff:127.0.0.1]", "http", "[0:0:0:0:0:ffff:127.0.0.1]", null, "", null, null, null),
            Arguments.of("http://[::ffff:127.0.0.1]", "http", "[::ffff:127.0.0.1]", null, "", null, null, null),

            // Scheme-less IPv6 host with port (default path)
            Arguments.of("//[2001:db8::1]:8080/", null, "[2001:db8::1]", "8080", "/", null, null, null),

            // Scheme-less IPv6 host with port (no path)
            Arguments.of("//[2001:db8::1]:8080", null, "[2001:db8::1]", "8080", "", null, null, null),

            // Scheme-less IPv6 host (default path)
            Arguments.of("//[2001:db8::1]/", null, "[2001:db8::1]", null, "/", null, null, null),

            // Scheme-less IPv6 host (no path)
            Arguments.of("//[2001:db8::1]", null, "[2001:db8::1]", null, "", null, null, null)
        );
    }

    @ParameterizedTest
    @MethodSource("parseData")
    public void testParseString(String input, String scheme, String host, Integer port, String path, String param, String query, String fragment)
    {
        HttpURI httpUri = HttpURI.from(input);

        try
        {
            // Ensure URI is valid enough that even java.net.URI will parse it.
            // If this fails, then we verify the resulting HttpURI in the URISyntaxException catch block.
            new URI(input);

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
            if (!input.contains(":ffff:127.0.0.1"))
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
    public void testParseURI(String input, String scheme, String host, Integer port, String path, String param, String query, String fragment)
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

        assertThat("[" + input + "] .scheme", httpUri.getScheme(), is(scheme));
        if (!input.contains(":ffff:127.0.0.1"))
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
    public void testCompareToJavaNetURI(String input, String scheme, String host, Integer port, String path, String param, String query, String fragment)
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
    public void testKnownPort()
    {
        assertThat(HttpURI.from("http", "server", 80, "/path").toString(), is("http://server/path"));
        assertThat(HttpURI.from("http", "server", 8888, "/path").toString(), is("http://server:8888/path"));
        assertThat(HttpURI.from("https", "server", 443, "/path").toString(), is("https://server/path"));
        assertThat(HttpURI.from("https", "server", 8443, "/path").toString(), is("https://server:8443/path"));
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

    @Test
    public void testKeepParam()
    {
        HttpURI orig = HttpURI.from("http://localhost/context/info;param=value");
        HttpURI built = HttpURI.build(orig).path("/context/info").asImmutable();
        assertThat(built.getParam(), is(orig.getParam()));
        assertThat(built.toString(), is(orig.toString()));

        built = HttpURI.build(orig).path("/context/info").param("param=value").asImmutable();
        assertThat(built.getParam(), is(orig.getParam()));
        assertThat(built.toString(), is(orig.toString()));
    }

    @Test
    public void testUriCompliance()
    {
        assertThat(UriCompliance.from(UriCompliance.DEFAULT.getName()), sameInstance(UriCompliance.DEFAULT));
    }

    public static Stream<Arguments> concatNormalizedURIShortCases()
    {
        return Stream.of(
            // Default behaviors of stripping a port number based on scheme
            Arguments.of("http", "example.org", 80, "http://example.org"),
            Arguments.of("https", "example.org", 443, "https://example.org"),
            Arguments.of("ws", "example.org", 80, "ws://example.org"),
            Arguments.of("wss", "example.org", 443, "wss://example.org"),
            // Mismatches between scheme and port
            Arguments.of("http", "example.org", 443, "http://example.org:443"),
            Arguments.of("https", "example.org", 80, "https://example.org:80"),
            Arguments.of("ws", "example.org", 443, "ws://example.org:443"),
            Arguments.of("wss", "example.org", 80, "wss://example.org:80"),
            // Odd ports
            Arguments.of("http", "example.org", 12345, "http://example.org:12345"),
            Arguments.of("https", "example.org", 54321, "https://example.org:54321"),
            Arguments.of("ws", "example.org", 6666, "ws://example.org:6666"),
            Arguments.of("wss", "example.org", 7777, "wss://example.org:7777"),
            // Non-lowercase Schemes
            Arguments.of("HTTP", "example.org", 8181, "http://example.org:8181"),
            Arguments.of("hTTps", "example.org", 443, "https://example.org"),
            Arguments.of("WS", "example.org", 8282, "ws://example.org:8282"),
            Arguments.of("wsS", "example.org", 8383, "wss://example.org:8383"),
            // Undefined Ports
            Arguments.of("http", "example.org", 0, "http://example.org"),
            Arguments.of("https", "example.org", -1, "https://example.org"),
            Arguments.of("ws", "example.org", -80, "ws://example.org"),
            Arguments.of("wss", "example.org", -2, "wss://example.org"),
            // Unrecognized (non-http) schemes
            Arguments.of("foo", "example.org", 0, "foo://example.org"),
            Arguments.of("ssh", "example.org", 22, "ssh://example.org"),
            Arguments.of("ftp", "example.org", 21, "ftp://example.org"),
            Arguments.of("ssh", "example.org", 2222, "ssh://example.org:2222"),
            Arguments.of("ftp", "example.org", 2121, "ftp://example.org:2121"),
            Arguments.of("file", "etc", -1, "file://etc")
        );
    }

    @ParameterizedTest
    @MethodSource("concatNormalizedURIShortCases")
    public void testFromShortAsStringNormalized(String scheme, String server, int port, String expectedStr)
    {
        HttpURI httpURI = HttpURI.from(scheme, server, port, null);
        assertThat(httpURI.asString(), is(expectedStr));
    }

    public static Stream<Arguments> concatNormalizedURICases()
    {
        return Stream.of(
            // Default behaviors of stripping a port number based on scheme
            Arguments.of("http", "example.org", 80, "/", null, null, "http://example.org/"),
            Arguments.of("https", "example.org", 443, "/", null, null, "https://example.org/"),
            Arguments.of("ws", "example.org", 80, "/", null, null, "ws://example.org/"),
            Arguments.of("wss", "example.org", 443, "/", null, null, "wss://example.org/"),
            // Mismatches between scheme and port
            Arguments.of("http", "example.org", 443, "/", null, null, "http://example.org:443/"),
            Arguments.of("https", "example.org", 80, "/", null, null, "https://example.org:80/"),
            Arguments.of("ws", "example.org", 443, "/", null, null, "ws://example.org:443/"),
            Arguments.of("wss", "example.org", 80, "/", null, null, "wss://example.org:80/"),
            // Odd ports
            Arguments.of("http", "example.org", 12345, "/", null, null, "http://example.org:12345/"),
            Arguments.of("https", "example.org", 54321, "/", null, null, "https://example.org:54321/"),
            Arguments.of("ws", "example.org", 6666, "/", null, null, "ws://example.org:6666/"),
            Arguments.of("wss", "example.org", 7777, "/", null, null, "wss://example.org:7777/"),
            // Non-lowercase Schemes
            Arguments.of("HTTP", "example.org", 8181, "/", null, null, "http://example.org:8181/"),
            Arguments.of("hTTps", "example.org", 443, "/", null, null, "https://example.org/"),
            Arguments.of("WS", "example.org", 8282, "/", null, null, "ws://example.org:8282/"),
            Arguments.of("wsS", "example.org", 8383, "/", null, null, "wss://example.org:8383/"),
            // Undefined scheme
            Arguments.of(null, "example.org", 8181, "/", null, null, "//example.org:8181/"),
            // Undefined Ports
            Arguments.of("http", "example.org", 0, "/", null, null, "http://example.org/"),
            Arguments.of("https", "example.org", -1, "/", null, null, "https://example.org/"),
            Arguments.of("ws", "example.org", -80, "/", null, null, "ws://example.org/"),
            Arguments.of("wss", "example.org", -2, "/", null, null, "wss://example.org/"),
            // Unrecognized (non-http) schemes
            Arguments.of("foo", "example.org", 0, "/", null, null, "foo://example.org/"),
            Arguments.of("ssh", "example.org", 22, "/", null, null, "ssh://example.org/"),
            Arguments.of("ftp", "example.org", 21, "/", null, null, "ftp://example.org/"),
            Arguments.of("ssh", "example.org", 2222, "/", null, null, "ssh://example.org:2222/"),
            Arguments.of("ftp", "example.org", 2121, "/", null, null, "ftp://example.org:2121/"),
            // Path choices
            Arguments.of("http", "example.org", 0, "/a/b/c/d", null, null, "http://example.org/a/b/c/d"),
            Arguments.of("http", "example.org", 0, "/a%20b/c%20d", null, null, "http://example.org/a%20b/c%20d"),
            Arguments.of("http", "example.org", 0, "/foo%2Fbaz", null, null, "http://example.org/foo%2Fbaz"),
            Arguments.of("http", "example.org", 0, "/foo%252Fbaz", null, null, "http://example.org/foo%252Fbaz"),
            // Query specified
            Arguments.of("http", "example.org", 0, "/", "a=b", null, "http://example.org/?a=b"),
            Arguments.of("http", "example.org", 0, "/documentation/latest/", "a=b", null, "http://example.org/documentation/latest/?a=b"),
            Arguments.of("http", "example.org", 0, null, "a=b", null, "http://example.org/?a=b"),
            Arguments.of("http", "example.org", 0, null, "", null, "http://example.org/?"),
            // Fragment specified
            Arguments.of("http", "example.org", 0, "/", null, "", "http://example.org/#"),
            Arguments.of("http", "example.org", 0, "/", null, "toc", "http://example.org/#toc"),
            Arguments.of("http", "example.org", 0, null, null, "toc", "http://example.org/#toc"),
            // Empty query & fragment - behavior matches java URI and URL
            Arguments.of("http", "example.org", 0, null, "", "", "http://example.org/?#")
        );
    }

    @ParameterizedTest
    @MethodSource("concatNormalizedURICases")
    public void testFromAsStringNormalized(String scheme, String server, int port, String path, String query, String fragment, String expectedStr)
    {
        HttpURI httpURI = HttpURI.from(scheme, server, port, path, query, fragment);
        assertThat(httpURI.asString(), is(expectedStr));
    }

    public static Stream<Arguments> fromStringAsStringCases()
    {
        return Stream.of(
            Arguments.of("http://localhost:4444/", "http://localhost:4444/"),
            Arguments.of("/foo/baz", "/foo/baz"),
            Arguments.of("/foo%2Fbaz", "/foo%2Fbaz"),
            Arguments.of("/foo%252Fbaz", "/foo%252Fbaz")
        );
    }

    @ParameterizedTest
    @MethodSource("fromStringAsStringCases")
    public void testFromStringAsString(String input, String expected)
    {
        HttpURI httpURI = HttpURI.from(input);
        assertThat(httpURI.asString(), is(expected));
    }

    /**
     * Tests of parameters that result in undesired behaviors.
     * {@link HttpURI#from(String, String, int, String)}
     */
    public static Stream<Arguments> fromBad()
    {
        return Stream.of(
            // bad schemes
            Arguments.of(null, "example.org", 0, "//example.org"),
            Arguments.of("", "example.org", 0, "://example.org"),
            Arguments.of("\t", "example.org", 0, "\t://example.org"),
            Arguments.of("    ", "example.org", 0, "    ://example.org"),
            Arguments.of("http^", "example.org", 0, "http^://example.org"),

            // bad ports
            Arguments.of("http", "example.org", 1_000_000, "http://example.org:1000000"),
            Arguments.of("ws", "example.org", -222333, "ws://example.org"), // negative port same as -1, i.e. not set.

            // bad servers
            Arguments.of("http", null, 0, "http:"),
            Arguments.of("http", "", 0, "http://"),
            Arguments.of("http", "\t", 0, "http://\t"),
            Arguments.of("http", "    ", 0, "http://    ")
        );
    }

    @ParameterizedTest
    @MethodSource("fromBad")
    public void testFromBad(String scheme, String server, int port, String expectedStr)
    {
        // TODO Consider whether we want to throw IllegalArgumentException instead
        HttpURI httpURI = HttpURI.from(scheme, server, port, null);
        assertThat(httpURI.asString(), is(expectedStr));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "://host/path",
        "\t://host/path",
        "  ://host/path",
        "unknown^://host/path",
        "http^://host/path"
    })
    public void testBadSchemes(String uri)
    {
        assertThrows(IllegalArgumentException.class, () -> HttpURI.from(uri));
    }

    public static Stream<String> badAuthorities()
    {
        return Stream.of(
            "https:// host/path",
            "https://h st/path",
            "https://h\000st/path",
            "https://h%GGst/path",
            "https://host%/path",
            "https://host%0/path",
            "https://host%u001f/path",
            "https://host%:8080/path",
            "https://host%0:8080/path",
            "https://user%@host/path",
            "https://user%0@host/path",
            "https://host:notport/path",
            "https://user@host:notport/path",
            "https://user:password@host:notport/path",
            "https://user @host.com/",
            // "https://user#@host.com/", TODO this might cause WhatWG compatibility issues
            "https://[notIpv6]",
            "https://bad[0::1::2::3::4]",
            "https://[notIpv6]/",
            "https://bad[0::1::2::3::4]/",

            "http://[fe80::1%25eth0]",
            "http://[fe80::1%251]",
            "http://[fe80::1%25eth0]/",
            "http://[fe80::1%251]/",

            "http://[vulndetector.com]",
            "http://hostone.com@[vulndetector.com]#hosttwo.com/",
            "http://hostone.com:80@[vulndetector.com]/",
            "http://[vulndetector.com]#@normal.com",
            "http://hostone.com\\\\[vulndetector.com]/",
            "http://[normal.com@]vulndetector.com/",
            "http://normal.com[user@vulndetector].com/",
            "http://normal.com[@]vulndetector.com/",

            // Ambiguous empty path
            "http://localhost;param",
            "http://localhost:8080;param"
        );
    }

    @ParameterizedTest
    @MethodSource("authoritiesNoPath")
    public void testAuthorityNoPath(String uri, String authority, String query, String fragment)
    {
        HttpURI httpURI = HttpURI.from(uri);
        assertThat(httpURI.getAuthority(), is(authority));
        assertThat(httpURI.getPath(), is(""));
        assertThat(httpURI.getQuery(), is(query));
        assertThat(httpURI.getFragment(), is(fragment));
    }

    public static Stream<Arguments> authoritiesNoPath()
    {
        return Stream.of(
            Arguments.of("http://good.com#@evil.com", "good.com", null, "@evil.com"),
            Arguments.of("http://good.com?@evil.com", "good.com", "@evil.com", null)
        );
    }

    @ParameterizedTest
    @MethodSource("badAuthorities")
    public void testBadAuthority(String uri)
    {
        assertThrows(IllegalArgumentException.class, () -> HttpURI.from(uri));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Hostname
        "https://username@host/path",
        "https://username:password@host/path",
        "https://username@host:8080/path",
        "https://username:password@host:8080/path",
        // IPv4
        "https://username@192.168.0.1/path",
        "https://username:password@192.168.0.1/path",
        "https://username@192.168.0.1:8080/path",
        "https://username:password@192.168.0.1:8080/path",
        // IPv6 - loopback
        "https://username@[::1]/path",
        "https://username:password@[::1]/path",
        "https://username@[::1]:8080/path",
        "https://username:password@[::1]:8080/path",
        // IPv6 - normal
        "https://username@[fdc7:2df6:7735:25ed:9e6b:ff:fec0:7d98]/path",
        "https://username:password@[fdc7:2df6:7735:25ed:9e6b:ff:fec0:7d98]/path",
        "https://username@[fdc7:2df6:7735:25ed:9e6b:ff:fec0:7d98]:8080/path",
        "https://username:password@[fdc7:2df6:7735:25ed:9e6b:ff:fec0:7d98]:8080/path",
    })
    public void testUserInfoViolation(String uri)
    {
        assertThat(HttpURI.from(uri).getViolations(), hasItem(Violation.USER_INFO));
    }
}
