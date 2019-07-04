//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URIUtil Tests.
 */
@SuppressWarnings("SpellCheckingInspection")
public class URIUtilTest
{
    @Test // TODO: Parameterize
    public void testEncodePath()
    {
        // test basic encode/decode
        StringBuilder buf = new StringBuilder();

        buf.setLength(0);
        URIUtil.encodePath(buf, "/foo%23+;,:=/b a r/?info ");
        assertEquals("/foo%2523+%3B,:=/b%20a%20r/%3Finfo%20", buf.toString());

        assertEquals("/foo%2523+%3B,:=/b%20a%20r/%3Finfo%20", URIUtil.encodePath("/foo%23+;,:=/b a r/?info "));

        buf.setLength(0);
        URIUtil.encodeString(buf, "foo%23;,:=b a r", ";,= ");
        assertEquals("foo%2523%3b%2c:%3db%20a%20r", buf.toString());

        buf.setLength(0);
        URIUtil.encodePath(buf, "/context/'list'/\"me\"/;<script>window.alert('xss');</script>");
        assertEquals("/context/%27list%27/%22me%22/%3B%3Cscript%3Ewindow.alert(%27xss%27)%3B%3C/script%3E", buf.toString());

        buf.setLength(0);
        URIUtil.encodePath(buf, "test\u00f6?\u00f6:\u00df");
        assertEquals("test%C3%B6%3F%C3%B6:%C3%9F", buf.toString());

        buf.setLength(0);
        URIUtil.encodePath(buf, "test?\u00f6?\u00f6:\u00df");
        assertEquals("test%3F%C3%B6%3F%C3%B6:%C3%9F", buf.toString());
    }

    @Test // TODO: Parameterize
    public void testDecodePath()
    {
        assertEquals(URIUtil.decodePath("xx/foo/barxx", 2, 8), "/foo/bar");
        assertEquals("/foo/bar", URIUtil.decodePath("/foo/bar"));
        assertEquals("/f o/b r", URIUtil.decodePath("/f%20o/b%20r"));
        assertEquals("/foo/bar", URIUtil.decodePath("/foo;ignore/bar;ignore"));
        assertEquals("/fää/bar", URIUtil.decodePath("/f\u00e4\u00e4;ignore/bar;ignore"));
        assertEquals("/f\u0629\u0629%23/bar", URIUtil.decodePath("/f%d8%a9%d8%a9%2523;ignore/bar;ignore"));

        assertEquals("foo%23;,:=b a r", URIUtil.decodePath("foo%2523%3b%2c:%3db%20a%20r;rubbish"));
        assertEquals("/foo/bar%23;,:=b a r=", URIUtil.decodePath("xxx/foo/bar%2523%3b%2c:%3db%20a%20r%3Dxxx;rubbish", 3, 35));
        assertEquals("f\u00e4\u00e4%23;,:=b a r=", URIUtil.decodePath("fää%2523%3b%2c:%3db%20a%20r%3D"));
        assertEquals("f\u0629\u0629%23;,:=b a r", URIUtil.decodePath("f%d8%a9%d8%a9%2523%3b%2c:%3db%20a%20r"));

        // Test for null character (real world ugly test case)
        byte[] oddBytes = {'/', 0x00, '/'};
        String odd = new String(oddBytes, StandardCharsets.ISO_8859_1);
        assertEquals(odd, URIUtil.decodePath("/%00/"));
    }

    @Test // TODO: Parameterize
    public void testAddEncodedPaths()
    {
        assertEquals(URIUtil.addEncodedPaths(null, null), null, "null+null");
        assertEquals(URIUtil.addEncodedPaths(null, ""), "", "null+");
        assertEquals(URIUtil.addEncodedPaths(null, "bbb"), "bbb", "null+bbb");
        assertEquals(URIUtil.addEncodedPaths(null, "/"), "/", "null+/");
        assertEquals(URIUtil.addEncodedPaths(null, "/bbb"), "/bbb", "null+/bbb");

        assertEquals(URIUtil.addEncodedPaths("", null), "", "+null");
        assertEquals(URIUtil.addEncodedPaths("", ""), "", "+");
        assertEquals(URIUtil.addEncodedPaths("", "bbb"), "bbb", "+bbb");
        assertEquals(URIUtil.addEncodedPaths("", "/"), "/", "+/");
        assertEquals(URIUtil.addEncodedPaths("", "/bbb"), "/bbb", "+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa", null), "aaa", "aaa+null");
        assertEquals(URIUtil.addEncodedPaths("aaa", ""), "aaa", "aaa+");
        assertEquals(URIUtil.addEncodedPaths("aaa", "bbb"), "aaa/bbb", "aaa+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa", "/"), "aaa/", "aaa+/");
        assertEquals(URIUtil.addEncodedPaths("aaa", "/bbb"), "aaa/bbb", "aaa+/bbb");

        assertEquals(URIUtil.addEncodedPaths("/", null), "/", "/+null");
        assertEquals(URIUtil.addEncodedPaths("/", ""), "/", "/+");
        assertEquals(URIUtil.addEncodedPaths("/", "bbb"), "/bbb", "/+bbb");
        assertEquals(URIUtil.addEncodedPaths("/", "/"), "/", "/+/");
        assertEquals(URIUtil.addEncodedPaths("/", "/bbb"), "/bbb", "/+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa/", null), "aaa/", "aaa/+null");
        assertEquals(URIUtil.addEncodedPaths("aaa/", ""), "aaa/", "aaa/+");
        assertEquals(URIUtil.addEncodedPaths("aaa/", "bbb"), "aaa/bbb", "aaa/+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa/", "/"), "aaa/", "aaa/+/");
        assertEquals(URIUtil.addEncodedPaths("aaa/", "/bbb"), "aaa/bbb", "aaa/+/bbb");

        assertEquals(URIUtil.addEncodedPaths(";JS", null), ";JS", ";JS+null");
        assertEquals(URIUtil.addEncodedPaths(";JS", ""), ";JS", ";JS+");
        assertEquals(URIUtil.addEncodedPaths(";JS", "bbb"), "bbb;JS", ";JS+bbb");
        assertEquals(URIUtil.addEncodedPaths(";JS", "/"), "/;JS", ";JS+/");
        assertEquals(URIUtil.addEncodedPaths(";JS", "/bbb"), "/bbb;JS", ";JS+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa;JS", null), "aaa;JS", "aaa;JS+null");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS", ""), "aaa;JS", "aaa;JS+");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS", "bbb"), "aaa/bbb;JS", "aaa;JS+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS", "/"), "aaa/;JS", "aaa;JS+/");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS", "/bbb"), "aaa/bbb;JS", "aaa;JS+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa/;JS", null), "aaa/;JS", "aaa;JS+null");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS", ""), "aaa/;JS", "aaa;JS+");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS", "bbb"), "aaa/bbb;JS", "aaa;JS+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS", "/"), "aaa/;JS", "aaa;JS+/");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS", "/bbb"), "aaa/bbb;JS", "aaa;JS+/bbb");

        assertEquals(URIUtil.addEncodedPaths("?A=1", null), "?A=1", "?A=1+null");
        assertEquals(URIUtil.addEncodedPaths("?A=1", ""), "?A=1", "?A=1+");
        assertEquals(URIUtil.addEncodedPaths("?A=1", "bbb"), "bbb?A=1", "?A=1+bbb");
        assertEquals(URIUtil.addEncodedPaths("?A=1", "/"), "/?A=1", "?A=1+/");
        assertEquals(URIUtil.addEncodedPaths("?A=1", "/bbb"), "/bbb?A=1", "?A=1+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa?A=1", null), "aaa?A=1", "aaa?A=1+null");
        assertEquals(URIUtil.addEncodedPaths("aaa?A=1", ""), "aaa?A=1", "aaa?A=1+");
        assertEquals(URIUtil.addEncodedPaths("aaa?A=1", "bbb"), "aaa/bbb?A=1", "aaa?A=1+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa?A=1", "/"), "aaa/?A=1", "aaa?A=1+/");
        assertEquals(URIUtil.addEncodedPaths("aaa?A=1", "/bbb"), "aaa/bbb?A=1", "aaa?A=1+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa/?A=1", null), "aaa/?A=1", "aaa?A=1+null");
        assertEquals(URIUtil.addEncodedPaths("aaa/?A=1", ""), "aaa/?A=1", "aaa?A=1+");
        assertEquals(URIUtil.addEncodedPaths("aaa/?A=1", "bbb"), "aaa/bbb?A=1", "aaa?A=1+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa/?A=1", "/"), "aaa/?A=1", "aaa?A=1+/");
        assertEquals(URIUtil.addEncodedPaths("aaa/?A=1", "/bbb"), "aaa/bbb?A=1", "aaa?A=1+/bbb");

        assertEquals(URIUtil.addEncodedPaths(";JS?A=1", null), ";JS?A=1", ";JS?A=1+null");
        assertEquals(URIUtil.addEncodedPaths(";JS?A=1", ""), ";JS?A=1", ";JS?A=1+");
        assertEquals(URIUtil.addEncodedPaths(";JS?A=1", "bbb"), "bbb;JS?A=1", ";JS?A=1+bbb");
        assertEquals(URIUtil.addEncodedPaths(";JS?A=1", "/"), "/;JS?A=1", ";JS?A=1+/");
        assertEquals(URIUtil.addEncodedPaths(";JS?A=1", "/bbb"), "/bbb;JS?A=1", ";JS?A=1+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa;JS?A=1", null), "aaa;JS?A=1", "aaa;JS?A=1+null");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS?A=1", ""), "aaa;JS?A=1", "aaa;JS?A=1+");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS?A=1", "bbb"), "aaa/bbb;JS?A=1", "aaa;JS?A=1+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS?A=1", "/"), "aaa/;JS?A=1", "aaa;JS?A=1+/");
        assertEquals(URIUtil.addEncodedPaths("aaa;JS?A=1", "/bbb"), "aaa/bbb;JS?A=1", "aaa;JS?A=1+/bbb");

        assertEquals(URIUtil.addEncodedPaths("aaa/;JS?A=1", null), "aaa/;JS?A=1", "aaa;JS?A=1+null");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS?A=1", ""), "aaa/;JS?A=1", "aaa;JS?A=1+");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS?A=1", "bbb"), "aaa/bbb;JS?A=1", "aaa;JS?A=1+bbb");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS?A=1", "/"), "aaa/;JS?A=1", "aaa;JS?A=1+/");
        assertEquals(URIUtil.addEncodedPaths("aaa/;JS?A=1", "/bbb"), "aaa/bbb;JS?A=1", "aaa;JS?A=1+/bbb");
    }

    @Test // TODO: Parameterize
    public void testAddDecodedPaths()
    {
        assertEquals(URIUtil.addPaths(null, null), null, "null+null");
        assertEquals(URIUtil.addPaths(null, ""), "", "null+");
        assertEquals(URIUtil.addPaths(null, "bbb"), "bbb", "null+bbb");
        assertEquals(URIUtil.addPaths(null, "/"), "/", "null+/");
        assertEquals(URIUtil.addPaths(null, "/bbb"), "/bbb", "null+/bbb");

        assertEquals(URIUtil.addPaths("", null), "", "+null");
        assertEquals(URIUtil.addPaths("", ""), "", "+");
        assertEquals(URIUtil.addPaths("", "bbb"), "bbb", "+bbb");
        assertEquals(URIUtil.addPaths("", "/"), "/", "+/");
        assertEquals(URIUtil.addPaths("", "/bbb"), "/bbb", "+/bbb");

        assertEquals(URIUtil.addPaths("aaa", null), "aaa", "aaa+null");
        assertEquals(URIUtil.addPaths("aaa", ""), "aaa", "aaa+");
        assertEquals(URIUtil.addPaths("aaa", "bbb"), "aaa/bbb", "aaa+bbb");
        assertEquals(URIUtil.addPaths("aaa", "/"), "aaa/", "aaa+/");
        assertEquals(URIUtil.addPaths("aaa", "/bbb"), "aaa/bbb", "aaa+/bbb");

        assertEquals(URIUtil.addPaths("/", null), "/", "/+null");
        assertEquals(URIUtil.addPaths("/", ""), "/", "/+");
        assertEquals(URIUtil.addPaths("/", "bbb"), "/bbb", "/+bbb");
        assertEquals(URIUtil.addPaths("/", "/"), "/", "/+/");
        assertEquals(URIUtil.addPaths("/", "/bbb"), "/bbb", "/+/bbb");

        assertEquals(URIUtil.addPaths("aaa/", null), "aaa/", "aaa/+null");
        assertEquals(URIUtil.addPaths("aaa/", ""), "aaa/", "aaa/+");
        assertEquals(URIUtil.addPaths("aaa/", "bbb"), "aaa/bbb", "aaa/+bbb");
        assertEquals(URIUtil.addPaths("aaa/", "/"), "aaa/", "aaa/+/");
        assertEquals(URIUtil.addPaths("aaa/", "/bbb"), "aaa/bbb", "aaa/+/bbb");

        assertEquals(URIUtil.addPaths(";JS", null), ";JS", ";JS+null");
        assertEquals(URIUtil.addPaths(";JS", ""), ";JS", ";JS+");
        assertEquals(URIUtil.addPaths(";JS", "bbb"), ";JS/bbb", ";JS+bbb");
        assertEquals(URIUtil.addPaths(";JS", "/"), ";JS/", ";JS+/");
        assertEquals(URIUtil.addPaths(";JS", "/bbb"), ";JS/bbb", ";JS+/bbb");

        assertEquals(URIUtil.addPaths("aaa;JS", null), "aaa;JS", "aaa;JS+null");
        assertEquals(URIUtil.addPaths("aaa;JS", ""), "aaa;JS", "aaa;JS+");
        assertEquals(URIUtil.addPaths("aaa;JS", "bbb"), "aaa;JS/bbb", "aaa;JS+bbb");
        assertEquals(URIUtil.addPaths("aaa;JS", "/"), "aaa;JS/", "aaa;JS+/");
        assertEquals(URIUtil.addPaths("aaa;JS", "/bbb"), "aaa;JS/bbb", "aaa;JS+/bbb");

        assertEquals(URIUtil.addPaths("aaa/;JS", null), "aaa/;JS", "aaa;JS+null");
        assertEquals(URIUtil.addPaths("aaa/;JS", ""), "aaa/;JS", "aaa;JS+");
        assertEquals(URIUtil.addPaths("aaa/;JS", "bbb"), "aaa/;JS/bbb", "aaa;JS+bbb");
        assertEquals(URIUtil.addPaths("aaa/;JS", "/"), "aaa/;JS/", "aaa;JS+/");
        assertEquals(URIUtil.addPaths("aaa/;JS", "/bbb"), "aaa/;JS/bbb", "aaa;JS+/bbb");

        assertEquals(URIUtil.addPaths("?A=1", null), "?A=1", "?A=1+null");
        assertEquals(URIUtil.addPaths("?A=1", ""), "?A=1", "?A=1+");
        assertEquals(URIUtil.addPaths("?A=1", "bbb"), "?A=1/bbb", "?A=1+bbb");
        assertEquals(URIUtil.addPaths("?A=1", "/"), "?A=1/", "?A=1+/");
        assertEquals(URIUtil.addPaths("?A=1", "/bbb"), "?A=1/bbb", "?A=1+/bbb");

        assertEquals(URIUtil.addPaths("aaa?A=1", null), "aaa?A=1", "aaa?A=1+null");
        assertEquals(URIUtil.addPaths("aaa?A=1", ""), "aaa?A=1", "aaa?A=1+");
        assertEquals(URIUtil.addPaths("aaa?A=1", "bbb"), "aaa?A=1/bbb", "aaa?A=1+bbb");
        assertEquals(URIUtil.addPaths("aaa?A=1", "/"), "aaa?A=1/", "aaa?A=1+/");
        assertEquals(URIUtil.addPaths("aaa?A=1", "/bbb"), "aaa?A=1/bbb", "aaa?A=1+/bbb");

        assertEquals(URIUtil.addPaths("aaa/?A=1", null), "aaa/?A=1", "aaa?A=1+null");
        assertEquals(URIUtil.addPaths("aaa/?A=1", ""), "aaa/?A=1", "aaa?A=1+");
        assertEquals(URIUtil.addPaths("aaa/?A=1", "bbb"), "aaa/?A=1/bbb", "aaa?A=1+bbb");
        assertEquals(URIUtil.addPaths("aaa/?A=1", "/"), "aaa/?A=1/", "aaa?A=1+/");
        assertEquals(URIUtil.addPaths("aaa/?A=1", "/bbb"), "aaa/?A=1/bbb", "aaa?A=1+/bbb");
    }

    @Test // TODO: Parameterize
    public void testCompactPath()
    {
        assertEquals("/foo/bar", URIUtil.compactPath("/foo/bar"));
        assertEquals("/foo/bar?a=b//c", URIUtil.compactPath("/foo/bar?a=b//c"));

        assertEquals("/foo/bar", URIUtil.compactPath("//foo//bar"));
        assertEquals("/foo/bar?a=b//c", URIUtil.compactPath("//foo//bar?a=b//c"));

        assertEquals("/foo/bar", URIUtil.compactPath("/foo///bar"));
        assertEquals("/foo/bar?a=b//c", URIUtil.compactPath("/foo///bar?a=b//c"));
    }

    @Test // TODO: Parameterize
    public void testParentPath()
    {
        assertEquals("/aaa/", URIUtil.parentPath("/aaa/bbb/"), "parent /aaa/bbb/");
        assertEquals("/aaa/", URIUtil.parentPath("/aaa/bbb"), "parent /aaa/bbb");
        assertEquals("/", URIUtil.parentPath("/aaa/"), "parent /aaa/");
        assertEquals("/", URIUtil.parentPath("/aaa"), "parent /aaa");
        assertEquals(null, URIUtil.parentPath("/"), "parent /");
        assertEquals(null, URIUtil.parentPath(null), "parent null");
    }

    @Test // TODO: Parameterize
    public void testEqualsIgnoreEncoding()
    {
        assertTrue(URIUtil.equalsIgnoreEncodings("http://example.com/foo/bar", "http://example.com/foo/bar"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/barry's", "/barry%27s"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/barry%27s", "/barry's"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/barry%27s", "/barry%27s"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/b rry's", "/b%20rry%27s"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/b rry%27s", "/b%20rry's"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/b rry%27s", "/b%20rry%27s"));

        assertTrue(URIUtil.equalsIgnoreEncodings("/foo%2fbar", "/foo%2fbar"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/foo%2fbar", "/foo%2Fbar"));

        assertFalse(URIUtil.equalsIgnoreEncodings("ABC", "abc"));
        assertFalse(URIUtil.equalsIgnoreEncodings("/barry's", "/barry%26s"));

        assertFalse(URIUtil.equalsIgnoreEncodings("/foo/bar", "/foo%2fbar"));
        assertFalse(URIUtil.equalsIgnoreEncodings("/foo2fbar", "/foo/bar"));
    }

    @Test // TODO: Parameterize
    public void testEqualsIgnoreEncoding_JarFile()
    {
        URI uriA = URI.create("jar:file:/path/to/main.jar!/META-INF/versions/");
        URI uriB = URI.create("jar:file:/path/to/main.jar!/META-INF/%76ersions/");
        assertTrue(URIUtil.equalsIgnoreEncodings(uriA, uriB));

        uriA = URI.create("JAR:FILE:/path/to/main.jar!/META-INF/versions/");
        uriB = URI.create("jar:file:/path/to/main.jar!/META-INF/versions/");
        assertTrue(URIUtil.equalsIgnoreEncodings(uriA, uriB));
    }

    @Test // TODO: Parameterize
    public void testJarSource() throws Exception
    {
        assertThat(URIUtil.getJarSource("file:///tmp/"), is("file:///tmp/"));
        assertThat(URIUtil.getJarSource("jar:file:///tmp/foo.jar"), is("file:///tmp/foo.jar"));
        assertThat(URIUtil.getJarSource("jar:file:///tmp/foo.jar!/some/path"), is("file:///tmp/foo.jar"));
        assertThat(URIUtil.getJarSource(new URI("file:///tmp/")), is(new URI("file:///tmp/")));
        assertThat(URIUtil.getJarSource(new URI("jar:file:///tmp/foo.jar")), is(new URI("file:///tmp/foo.jar")));
        assertThat(URIUtil.getJarSource(new URI("jar:file:///tmp/foo.jar!/some/path")), is(new URI("file:///tmp/foo.jar")));
    }

    public static Stream<String[]> encodeSpaces()
    {
        List<String[]> data = new ArrayList<>();

        // [raw, expected]

        // null
        data.add(new String[]{null, null});

        // no spaces
        data.add(new String[]{"abc", "abc"});

        // match
        data.add(new String[]{"a c", "a%20c"});
        data.add(new String[]{"   ", "%20%20%20"});
        data.add(new String[]{"a%20space", "a%20space"});

        return data.stream();
    }

    @ParameterizedTest
    @MethodSource(value = "encodeSpaces")
    public void testEncodeSpaces(String raw, String expected)
    {
        assertThat(URIUtil.encodeSpaces(raw), is(expected));
    }

    public static Stream<String[]> encodeSpecific()
    {
        List<String[]> data = new ArrayList<>();

        // [raw, chars, expected]

        // null input
        data.add(new String[]{null, null, null});

        // null chars
        data.add(new String[]{"abc", null, "abc"});

        // empty chars
        data.add(new String[]{"abc", "", "abc"});

        // no matches
        data.add(new String[]{"abc", ".;", "abc"});
        data.add(new String[]{"xyz", ".;", "xyz"});
        data.add(new String[]{":::", ".;", ":::"});

        // matches
        data.add(new String[]{"a c", " ", "a%20c"});
        data.add(new String[]{"name=value", "=", "name%3Dvalue"});
        data.add(new String[]{"This has fewer then 10% hits.", ".%", "This has fewer then 10%25 hits%2E"});

        // partially encoded already
        data.add(new String[]{"a%20name=value%20pair", "=", "a%20name%3Dvalue%20pair"});
        data.add(new String[]{"a%20name=value%20pair", "=%", "a%2520name%3Dvalue%2520pair"});

        return data.stream();
    }

    @ParameterizedTest
    @MethodSource(value = "encodeSpecific")
    public void testEncodeSpecific(String raw, String chars, String expected)
    {
        assertThat(URIUtil.encodeSpecific(raw, chars), is(expected));
    }

    public static Stream<String[]> decodeSpecific()
    {
        List<String[]> data = new ArrayList<>();

        // [raw, chars, expected]

        // null input
        data.add(new String[]{null, null, null});

        // null chars
        data.add(new String[]{"abc", null, "abc"});

        // empty chars
        data.add(new String[]{"abc", "", "abc"});

        // no matches
        data.add(new String[]{"abc", ".;", "abc"});
        data.add(new String[]{"xyz", ".;", "xyz"});
        data.add(new String[]{":::", ".;", ":::"});

        // matches
        data.add(new String[]{"a%20c", " ", "a c"});
        data.add(new String[]{"name%3Dvalue", "=", "name=value"});
        data.add(new String[]{"This has fewer then 10%25 hits%2E", ".%", "This has fewer then 10% hits."});

        // partially decode
        data.add(new String[]{"a%20name%3Dvalue%20pair", "=", "a%20name=value%20pair"});
        data.add(new String[]{"a%2520name%3Dvalue%2520pair", "=%", "a%20name=value%20pair"});

        return data.stream();
    }

    @ParameterizedTest
    @MethodSource(value = "decodeSpecific")
    public void testDecodeSpecific(String raw, String chars, String expected)
    {
        assertThat(URIUtil.decodeSpecific(raw, chars), is(expected));
    }
}
