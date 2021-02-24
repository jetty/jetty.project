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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpURI.Ambiguous;
import org.eclipse.jetty.util.MultiMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HttpURITest
{
    @Test
    public void testInvalidAddress() throws Exception
    {
        assertInvalidURI("http://[ffff::1:8080/", "Invalid URL; no closing ']' -- should throw exception");
        assertInvalidURI("**", "only '*', not '**'");
        assertInvalidURI("*/", "only '*', not '*/'");
    }

    private void assertInvalidURI(String invalidURI, String message)
    {
        HttpURI uri = new HttpURI();
        try
        {
            uri.parse(invalidURI);
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
        HttpURI uri = new HttpURI();

        uri.parse("*");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("*"));

        uri.parse("/foo/bar");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("/foo/bar"));

        uri.parse("//foo/bar");
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));

        uri.parse("http://foo/bar");
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));
    }

    @Test
    public void testParseRequestTarget()
    {
        HttpURI uri = new HttpURI();

        uri.parseRequestTarget("GET", "*");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("*"));

        uri.parseRequestTarget("GET", "/foo/bar");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("/foo/bar"));

        uri.parseRequestTarget("GET", "//foo/bar");
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("//foo/bar"));

        uri.parseRequestTarget("GET", "http://foo/bar");
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));
    }

    @Test
    public void testExtB() throws Exception
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        for (String value : new String[]{"a", "abcdABCD", "\u00C0", "\u697C", "\uD869\uDED5", "\uD840\uDC08"})
        {
            HttpURI uri = new HttpURI("/path?value=" + URLEncoder.encode(value, "UTF-8"));

            MultiMap<String> parameters = new MultiMap<>();
            uri.decodeQueryTo(parameters, StandardCharsets.UTF_8);
            assertEquals(value, parameters.getString("value"));
        }
    }

    @Test
    public void testAt() throws Exception
    {
        HttpURI uri = new HttpURI("/@foo/bar");
        assertEquals("/@foo/bar", uri.getPath());
    }

    @Test
    public void testParams() throws Exception
    {
        HttpURI uri = new HttpURI("/foo/bar");
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals(null, uri.getParam());

        uri = new HttpURI("/foo/bar;jsessionid=12345");
        assertEquals("/foo/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345?name=value");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345#target");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());
    }

    @Test
    public void testMutableURI()
    {
        HttpURI uri = new HttpURI("/foo/bar");
        assertEquals("/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri.setScheme("http");
        assertEquals("http:/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri.setAuthority("host", 0);
        assertEquals("http://host/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri.setAuthority("host", 8888);
        assertEquals("http://host:8888/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri.setPathQuery("/f%30%30;p0/bar;p1;p2");
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals(null, uri.getQuery());

        uri.setPathQuery("/f%30%30;p0/bar;p1;p2?name=value");
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?name=value", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals("name=value", uri.getQuery());

        uri.setQuery("other=123456");
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?other=123456", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals("other=123456", uri.getQuery());
    }

    @Test
    public void testSchemeAndOrAuthority() throws Exception
    {
        HttpURI uri = new HttpURI("/path/info");
        assertEquals("/path/info", uri.toString());

        uri.setAuthority("host", 0);
        assertEquals("//host/path/info", uri.toString());

        uri.setAuthority("host", 8888);
        assertEquals("//host:8888/path/info", uri.toString());

        uri.setScheme("http");
        assertEquals("http://host:8888/path/info", uri.toString());

        uri.setAuthority(null, 0);
        assertEquals("http:/path/info", uri.toString());
    }

    @Test
    public void testSetters() throws Exception
    {
        HttpURI uri = new HttpURI();
        assertEquals("", uri.toString());

        uri = new HttpURI(null, null, 0, null, null, null, null);
        assertEquals("", uri.toString());

        uri.setPath("/path/info");
        assertEquals("/path/info", uri.toString());

        uri.setAuthority("host", 8080);
        assertEquals("//host:8080/path/info", uri.toString());

        uri.setParam("param");
        assertEquals("//host:8080/path/info;param", uri.toString());

        uri.setQuery("a=b");
        assertEquals("//host:8080/path/info;param?a=b", uri.toString());

        uri.setScheme("http");
        assertEquals("http://host:8080/path/info;param?a=b", uri.toString());

        uri.setPathQuery("/other;xxx/path;ppp?query");
        assertEquals("http://host:8080/other;xxx/path;ppp?query", uri.toString());

        assertThat(uri.getScheme(), is("http"));
        assertThat(uri.getAuthority(), is("host:8080"));
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(8080));
        assertThat(uri.getPath(), is("/other;xxx/path;ppp"));
        assertThat(uri.getDecodedPath(), is("/other/path"));
        assertThat(uri.getParam(), is("ppp"));
        assertThat(uri.getQuery(), is("query"));
        assertThat(uri.getPathQuery(), is("/other;xxx/path;ppp?query"));

        uri.setPathQuery(null);
        assertEquals("http://host:8080?query", uri.toString()); // Yes silly result!

        uri.setQuery(null);
        assertEquals("http://host:8080", uri.toString());

        uri.setPathQuery("/other;xxx/path;ppp?query");
        assertEquals("http://host:8080/other;xxx/path;ppp?query", uri.toString());

        uri.setScheme(null);
        assertEquals("//host:8080/other;xxx/path;ppp?query", uri.toString());

        uri.setAuthority(null, -1);
        assertEquals("/other;xxx/path;ppp?query", uri.toString());

        uri.setParam(null);
        assertEquals("/other;xxx/path?query", uri.toString());

        uri.setQuery(null);
        assertEquals("/other;xxx/path", uri.toString());

        uri.setPath(null);
        assertEquals("", uri.toString());
    }

    public static Stream<Arguments> decodePathTests()
    {
        return Arrays.stream(new Object[][]
            {
                // Simple path example
                {"http://host/path/info", "/path/info", EnumSet.noneOf(Ambiguous.class)},
                {"//host/path/info", "/path/info", EnumSet.noneOf(Ambiguous.class)},
                {"/path/info", "/path/info", EnumSet.noneOf(Ambiguous.class)},

                // legal non ambiguous relative paths
                {"http://host/../path/info", null, EnumSet.noneOf(Ambiguous.class)},
                {"http://host/path/../info", "/info", EnumSet.noneOf(Ambiguous.class)},
                {"http://host/path/./info", "/path/info", EnumSet.noneOf(Ambiguous.class)},
                {"//host/path/../info", "/info", EnumSet.noneOf(Ambiguous.class)},
                {"//host/path/./info", "/path/info", EnumSet.noneOf(Ambiguous.class)},
                {"/path/../info", "/info", EnumSet.noneOf(Ambiguous.class)},
                {"/path/./info", "/path/info", EnumSet.noneOf(Ambiguous.class)},
                {"path/../info", "info", EnumSet.noneOf(Ambiguous.class)},
                {"path/./info", "path/info", EnumSet.noneOf(Ambiguous.class)},

                // illegal paths
                {"//host/../path/info", null, EnumSet.noneOf(Ambiguous.class)},
                {"/../path/info", null, EnumSet.noneOf(Ambiguous.class)},
                {"../path/info", null, EnumSet.noneOf(Ambiguous.class)},
                {"/path/%XX/info", null, EnumSet.noneOf(Ambiguous.class)},
                {"/path/%2/F/info", null, EnumSet.noneOf(Ambiguous.class)},

                // ambiguous dot encodings
                {"scheme://host/path/%2e/info", "/path/./info", EnumSet.of(Ambiguous.SEGMENT)},
                {"scheme:/path/%2e/info", "/path/./info", EnumSet.of(Ambiguous.SEGMENT)},
                {"/path/%2e/info", "/path/./info", EnumSet.of(Ambiguous.SEGMENT)},
                {"path/%2e/info/", "path/./info/", EnumSet.of(Ambiguous.SEGMENT)},
                {"/path/%2e%2e/info", "/path/../info", EnumSet.of(Ambiguous.SEGMENT)},
                {"/path/%2e%2e;/info", "/path/../info", EnumSet.of(Ambiguous.SEGMENT)},
                {"/path/%2e%2e;param/info", "/path/../info", EnumSet.of(Ambiguous.SEGMENT)},
                {"/path/%2e%2e;param;other/info;other", "/path/../info", EnumSet.of(Ambiguous.SEGMENT)},
                {"%2e/info", "./info", EnumSet.of(Ambiguous.SEGMENT)},
                {"%2e%2e/info", "../info", EnumSet.of(Ambiguous.SEGMENT)},
                {"%2e%2e;/info", "../info", EnumSet.of(Ambiguous.SEGMENT)},
                {"%2e", ".", EnumSet.of(Ambiguous.SEGMENT)},
                {"%2e.", "..", EnumSet.of(Ambiguous.SEGMENT)},
                {".%2e", "..", EnumSet.of(Ambiguous.SEGMENT)},
                {"%2e%2e", "..", EnumSet.of(Ambiguous.SEGMENT)},

                // ambiguous parameter inclusions
                {"/path/.;/info", "/path/./info", EnumSet.of(Ambiguous.PARAM)},
                {"/path/.;param/info", "/path/./info", EnumSet.of(Ambiguous.PARAM)},
                {"/path/..;/info", "/path/../info", EnumSet.of(Ambiguous.PARAM)},
                {"/path/..;param/info", "/path/../info", EnumSet.of(Ambiguous.PARAM)},
                {".;/info", "./info", EnumSet.of(Ambiguous.PARAM)},
                {".;param/info", "./info", EnumSet.of(Ambiguous.PARAM)},
                {"..;/info", "../info", EnumSet.of(Ambiguous.PARAM)},
                {"..;param/info", "../info", EnumSet.of(Ambiguous.PARAM)},

                // ambiguous segment separators
                {"/path/%2f/info", "/path///info", EnumSet.of(Ambiguous.SEPARATOR)},
                {"%2f/info", "//info", EnumSet.of(Ambiguous.SEPARATOR)},
                {"%2F/info", "//info", EnumSet.of(Ambiguous.SEPARATOR)},
                {"/path/%2f../info", "/path//../info", EnumSet.of(Ambiguous.SEPARATOR)},

                // combinations
                {"/path/%2f/..;/info", "/path///../info", EnumSet.of(Ambiguous.SEPARATOR, Ambiguous.PARAM)},
                {"/path/%2f/..;/%2e/info", "/path///.././info", EnumSet.of(Ambiguous.SEPARATOR, Ambiguous.PARAM, Ambiguous.SEGMENT)},

                // Non ascii characters
                {"http://localhost:9000/x\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/x\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", EnumSet.noneOf(Ambiguous.class)},
                {"http://localhost:9000/\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", "/\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", EnumSet.noneOf(Ambiguous.class)},
            }).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("decodePathTests")
    public void testDecodedPath(String input, String decodedPath, EnumSet<Ambiguous> expected)
    {
        try
        {
            HttpURI uri = new HttpURI(input);
            assertThat(uri.getDecodedPath(), is(decodedPath));
            assertThat(uri.isAmbiguous(), is(!expected.isEmpty()));
            assertThat(uri.hasAmbiguousSegment(), is(expected.contains(Ambiguous.SEGMENT)));
            assertThat(uri.hasAmbiguousSeparator(), is(expected.contains(Ambiguous.SEPARATOR)));
            assertThat(uri.hasAmbiguousParameter(), is(expected.contains(Ambiguous.PARAM)));
        }
        catch (Exception e)
        {
            assertThat(decodedPath, nullValue());
        }
    }
}
