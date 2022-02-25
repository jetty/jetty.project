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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URIUtil Tests.
 */
@SuppressWarnings("SpellCheckingInspection")
@ExtendWith(WorkDirExtension.class)
public class URIUtilTest
{
    private static final Logger LOG = LoggerFactory.getLogger(URIUtilTest.class);
    public WorkDir workDir;

    public static Stream<Arguments> encodePathSource()
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        return Stream.of(
            Arguments.of("/foo/\n/bar", "/foo/%0A/bar"),
            Arguments.of("/foo%23+;,:=/b a r/?info ", "/foo%2523+%3B,:=/b%20a%20r/%3Finfo%20"),
            Arguments.of("/context/'list'/\"me\"/;<script>window.alert('xss');</script>",
                "/context/%27list%27/%22me%22/%3B%3Cscript%3Ewindow.alert(%27xss%27)%3B%3C/script%3E"),
            Arguments.of("test\u00f6?\u00f6:\u00df", "test%C3%B6%3F%C3%B6:%C3%9F"),
            Arguments.of("test?\u00f6?\u00f6:\u00df", "test%3F%C3%B6%3F%C3%B6:%C3%9F")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("encodePathSource")
    public void testEncodePath(String rawPath, String expectedEncoded)
    {
        // test basic encode/decode
        StringBuilder buf = new StringBuilder();
        buf.setLength(0);
        URIUtil.encodePath(buf, rawPath);
        assertEquals(expectedEncoded, buf.toString());
    }

    @Test
    public void testEncodeString()
    {
        StringBuilder buf = new StringBuilder();
        buf.setLength(0);
        URIUtil.encodeString(buf, "foo%23;,:=b a r", ";,= ");
        assertEquals("foo%2523%3b%2c:%3db%20a%20r", buf.toString());
    }

    public static Stream<Arguments> decodePathSource()
    {
        List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of("/foo/bar", "/foo/bar"));

        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        arguments.add(Arguments.of("/f%20o/b%20r", "/f o/b r"));
        arguments.add(Arguments.of("fää%2523%3b%2c:%3db%20a%20r%3D", "f\u00e4\u00e4%23;,:=b a r="));
        arguments.add(Arguments.of("f%d8%a9%d8%a9%2523%3b%2c:%3db%20a%20r", "f\u0629\u0629%23;,:=b a r"));

        // path parameters should be ignored
        arguments.add(Arguments.of("/foo;ignore/bar;ignore", "/foo/bar"));
        arguments.add(Arguments.of("/f\u00e4\u00e4;ignore/bar;ignore", "/fää/bar"));
        arguments.add(Arguments.of("/f%d8%a9%d8%a9%2523;ignore/bar;ignore", "/f\u0629\u0629%23/bar"));
        arguments.add(Arguments.of("foo%2523%3b%2c:%3db%20a%20r;rubbish", "foo%23;,:=b a r"));

        // Test for null character (real world ugly test case)
        byte[] oddBytes = {'/', 0x00, '/'};
        String odd = new String(oddBytes, StandardCharsets.ISO_8859_1);
        arguments.add(Arguments.of("/%00/", odd));

        // Deprecated Microsoft Percent-U encoding
        arguments.add(Arguments.of("abc%u3040", "abc\u3040"));
        return arguments.stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("decodePathSource")
    public void testDecodePath(String encodedPath, String expectedPath)
    {
        String path = URIUtil.decodePath(encodedPath);
        assertEquals(expectedPath, path);
    }

    public static Stream<Arguments> decodeBadPathSource()
    {
        List<Arguments> arguments = new ArrayList<>();

        // Test for null character (real world ugly test case)
        // TODO is this a bad decoding or a bad URI ?
        // arguments.add(Arguments.of("/%00/"));

        // Deprecated Microsoft Percent-U encoding
        // TODO still supported for now ?
        // arguments.add(Arguments.of("abc%u3040"));

        // Bad %## encoding
        arguments.add(Arguments.of("abc%xyz"));

        // Incomplete %## encoding
        arguments.add(Arguments.of("abc%"));
        arguments.add(Arguments.of("abc%A"));

        // Invalid microsoft %u#### encoding
        arguments.add(Arguments.of("abc%uvwxyz"));
        arguments.add(Arguments.of("abc%uEFGHIJ"));

        // Incomplete microsoft %u#### encoding
        arguments.add(Arguments.of("abc%uABC"));
        arguments.add(Arguments.of("abc%uAB"));
        arguments.add(Arguments.of("abc%uA"));
        arguments.add(Arguments.of("abc%u"));

        // Invalid UTF-8 and ISO8859-1
        // TODO currently ISO8859 is too forgiving to detect these
        /*
        arguments.add(Arguments.of("abc%C3%28")); // invalid 2 octext sequence
        arguments.add(Arguments.of("abc%A0%A1")); // invalid 2 octext sequence
        arguments.add(Arguments.of("abc%e2%28%a1")); // invalid 3 octext sequence
        arguments.add(Arguments.of("abc%e2%82%28")); // invalid 3 octext sequence
        arguments.add(Arguments.of("abc%f0%28%8c%bc")); // invalid 4 octext sequence
        arguments.add(Arguments.of("abc%f0%90%28%bc")); // invalid 4 octext sequence
        arguments.add(Arguments.of("abc%f0%28%8c%28")); // invalid 4 octext sequence
        arguments.add(Arguments.of("abc%f8%a1%a1%a1%a1")); // valid sequence, but not unicode
        arguments.add(Arguments.of("abc%fc%a1%a1%a1%a1%a1")); // valid sequence, but not unicode
        arguments.add(Arguments.of("abc%f8%a1%a1%a1")); // incomplete sequence
         */

        return arguments.stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("decodeBadPathSource")
    public void testBadDecodePath(String encodedPath)
    {
        assertThrows(IllegalArgumentException.class, () -> URIUtil.decodePath(encodedPath));
    }

    @Test
    public void testDecodePathSubstring()
    {
        String path = URIUtil.decodePath("xx/foo/barxx", 2, 8);
        assertEquals("/foo/bar", path);

        path = URIUtil.decodePath("xxx/foo/bar%2523%3b%2c:%3db%20a%20r%3Dxxx;rubbish", 3, 35);
        assertEquals("/foo/bar%23;,:=b a r=", path);
    }

    public static Stream<Arguments> addEncodedPathsSource()
    {
        return Stream.of(
            Arguments.of(null, null, null),
            Arguments.of(null, "", ""),
            Arguments.of(null, "bbb", "bbb"),
            Arguments.of(null, "/", "/"),
            Arguments.of(null, "/bbb", "/bbb"),

            Arguments.of("", null, ""),
            Arguments.of("", "", ""),
            Arguments.of("", "bbb", "bbb"),
            Arguments.of("", "/", "/"),
            Arguments.of("", "/bbb", "/bbb"),

            Arguments.of("aaa", null, "aaa"),
            Arguments.of("aaa", "", "aaa"),
            Arguments.of("aaa", "bbb", "aaa/bbb"),
            Arguments.of("aaa", "/", "aaa/"),
            Arguments.of("aaa", "/bbb", "aaa/bbb"),

            Arguments.of("/", null, "/"),
            Arguments.of("/", "", "/"),
            Arguments.of("/", "bbb", "/bbb"),
            Arguments.of("/", "/", "/"),
            Arguments.of("/", "/bbb", "/bbb"),

            Arguments.of("aaa/", null, "aaa/"),
            Arguments.of("aaa/", "", "aaa/"),
            Arguments.of("aaa/", "bbb", "aaa/bbb"),
            Arguments.of("aaa/", "/", "aaa/"),
            Arguments.of("aaa/", "/bbb", "aaa/bbb"),

            Arguments.of(";JS", null, ";JS"),
            Arguments.of(";JS", "", ";JS"),
            Arguments.of(";JS", "bbb", "bbb;JS"),
            Arguments.of(";JS", "/", "/;JS"),
            Arguments.of(";JS", "/bbb", "/bbb;JS"),

            Arguments.of("aaa;JS", null, "aaa;JS"),
            Arguments.of("aaa;JS", "", "aaa;JS"),
            Arguments.of("aaa;JS", "bbb", "aaa/bbb;JS"),
            Arguments.of("aaa;JS", "/", "aaa/;JS"),
            Arguments.of("aaa;JS", "/bbb", "aaa/bbb;JS"),

            Arguments.of("aaa/;JS", null, "aaa/;JS"),
            Arguments.of("aaa/;JS", "", "aaa/;JS"),
            Arguments.of("aaa/;JS", "bbb", "aaa/bbb;JS"),
            Arguments.of("aaa/;JS", "/", "aaa/;JS"),
            Arguments.of("aaa/;JS", "/bbb", "aaa/bbb;JS"),

            Arguments.of("?A=1", null, "?A=1"),
            Arguments.of("?A=1", "", "?A=1"),
            Arguments.of("?A=1", "bbb", "bbb?A=1"),
            Arguments.of("?A=1", "/", "/?A=1"),
            Arguments.of("?A=1", "/bbb", "/bbb?A=1"),

            Arguments.of("aaa?A=1", null, "aaa?A=1"),
            Arguments.of("aaa?A=1", "", "aaa?A=1"),
            Arguments.of("aaa?A=1", "bbb", "aaa/bbb?A=1"),
            Arguments.of("aaa?A=1", "/", "aaa/?A=1"),
            Arguments.of("aaa?A=1", "/bbb", "aaa/bbb?A=1"),

            Arguments.of("aaa/?A=1", null, "aaa/?A=1"),
            Arguments.of("aaa/?A=1", "", "aaa/?A=1"),
            Arguments.of("aaa/?A=1", "bbb", "aaa/bbb?A=1"),
            Arguments.of("aaa/?A=1", "/", "aaa/?A=1"),
            Arguments.of("aaa/?A=1", "/bbb", "aaa/bbb?A=1"),

            Arguments.of(";JS?A=1", null, ";JS?A=1"),
            Arguments.of(";JS?A=1", "", ";JS?A=1"),
            Arguments.of(";JS?A=1", "bbb", "bbb;JS?A=1"),
            Arguments.of(";JS?A=1", "/", "/;JS?A=1"),
            Arguments.of(";JS?A=1", "/bbb", "/bbb;JS?A=1"),

            Arguments.of("aaa;JS?A=1", null, "aaa;JS?A=1"),
            Arguments.of("aaa;JS?A=1", "", "aaa;JS?A=1"),
            Arguments.of("aaa;JS?A=1", "bbb", "aaa/bbb;JS?A=1"),
            Arguments.of("aaa;JS?A=1", "/", "aaa/;JS?A=1"),
            Arguments.of("aaa;JS?A=1", "/bbb", "aaa/bbb;JS?A=1"),

            Arguments.of("aaa/;JS?A=1", null, "aaa/;JS?A=1"),
            Arguments.of("aaa/;JS?A=1", "", "aaa/;JS?A=1"),
            Arguments.of("aaa/;JS?A=1", "bbb", "aaa/bbb;JS?A=1"),
            Arguments.of("aaa/;JS?A=1", "/", "aaa/;JS?A=1"),
            Arguments.of("aaa/;JS?A=1", "/bbb", "aaa/bbb;JS?A=1")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}+{1}")
    @MethodSource("addEncodedPathsSource")
    public void testAddEncodedPaths(String path1, String path2, String expected)
    {
        String actual = URIUtil.addEncodedPaths(path1, path2);
        assertEquals(expected, actual, String.format("%s+%s", path1, path2));
    }

    public static Stream<Arguments> addDecodedPathsSource()
    {
        return Stream.of(
            Arguments.of(null, null, null),
            Arguments.of(null, "", ""),
            Arguments.of(null, "bbb", "bbb"),
            Arguments.of(null, "/", "/"),
            Arguments.of(null, "/bbb", "/bbb"),

            Arguments.of("", null, ""),
            Arguments.of("", "", ""),
            Arguments.of("", "bbb", "bbb"),
            Arguments.of("", "/", "/"),
            Arguments.of("", "/bbb", "/bbb"),

            Arguments.of("aaa", null, "aaa"),
            Arguments.of("aaa", "", "aaa"),
            Arguments.of("aaa", "bbb", "aaa/bbb"),
            Arguments.of("aaa", "/", "aaa/"),
            Arguments.of("aaa", "/bbb", "aaa/bbb"),

            Arguments.of("/", null, "/"),
            Arguments.of("/", "", "/"),
            Arguments.of("/", "bbb", "/bbb"),
            Arguments.of("/", "/", "/"),
            Arguments.of("/", "/bbb", "/bbb"),

            Arguments.of("aaa/", null, "aaa/"),
            Arguments.of("aaa/", "", "aaa/"),
            Arguments.of("aaa/", "bbb", "aaa/bbb"),
            Arguments.of("aaa/", "/", "aaa/"),
            Arguments.of("aaa/", "/bbb", "aaa/bbb"),

            Arguments.of(";JS", null, ";JS"),
            Arguments.of(";JS", "", ";JS"),
            Arguments.of(";JS", "bbb", ";JS/bbb"),
            Arguments.of(";JS", "/", ";JS/"),
            Arguments.of(";JS", "/bbb", ";JS/bbb"),

            Arguments.of("aaa;JS", null, "aaa;JS"),
            Arguments.of("aaa;JS", "", "aaa;JS"),
            Arguments.of("aaa;JS", "bbb", "aaa;JS/bbb"),
            Arguments.of("aaa;JS", "/", "aaa;JS/"),
            Arguments.of("aaa;JS", "/bbb", "aaa;JS/bbb"),

            Arguments.of("aaa/;JS", null, "aaa/;JS"),
            Arguments.of("aaa/;JS", "", "aaa/;JS"),
            Arguments.of("aaa/;JS", "bbb", "aaa/;JS/bbb"),
            Arguments.of("aaa/;JS", "/", "aaa/;JS/"),
            Arguments.of("aaa/;JS", "/bbb", "aaa/;JS/bbb"),

            Arguments.of("?A=1", null, "?A=1"),
            Arguments.of("?A=1", "", "?A=1"),
            Arguments.of("?A=1", "bbb", "?A=1/bbb"),
            Arguments.of("?A=1", "/", "?A=1/"),
            Arguments.of("?A=1", "/bbb", "?A=1/bbb"),

            Arguments.of("aaa?A=1", null, "aaa?A=1"),
            Arguments.of("aaa?A=1", "", "aaa?A=1"),
            Arguments.of("aaa?A=1", "bbb", "aaa?A=1/bbb"),
            Arguments.of("aaa?A=1", "/", "aaa?A=1/"),
            Arguments.of("aaa?A=1", "/bbb", "aaa?A=1/bbb"),

            Arguments.of("aaa/?A=1", null, "aaa/?A=1"),
            Arguments.of("aaa/?A=1", "", "aaa/?A=1"),
            Arguments.of("aaa/?A=1", "bbb", "aaa/?A=1/bbb"),
            Arguments.of("aaa/?A=1", "/", "aaa/?A=1/"),
            Arguments.of("aaa/?A=1", "/bbb", "aaa/?A=1/bbb")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}+{1}")
    @MethodSource("addDecodedPathsSource")
    public void testAddDecodedPaths(String path1, String path2, String expected)
    {
        String actual = URIUtil.addPaths(path1, path2);
        assertEquals(expected, actual, String.format("%s+%s", path1, path2));
    }

    public static Stream<Arguments> compactPathSource()
    {
        return Stream.of(
            Arguments.of("/foo/bar", "/foo/bar"),
            Arguments.of("/foo/bar?a=b//c", "/foo/bar?a=b//c"),

            Arguments.of("//foo//bar", "/foo/bar"),
            Arguments.of("//foo//bar?a=b//c", "/foo/bar?a=b//c"),

            Arguments.of("/foo///bar", "/foo/bar"),
            Arguments.of("/foo///bar?a=b//c", "/foo/bar?a=b//c")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("compactPathSource")
    public void testCompactPath(String path, String expected)
    {
        String actual = URIUtil.compactPath(path);
        assertEquals(expected, actual);
    }

    public static Stream<Arguments> parentPathSource()
    {
        return Stream.of(
            Arguments.of("/aaa/bbb/", "/aaa/"),
            Arguments.of("/aaa/bbb", "/aaa/"),
            Arguments.of("/aaa/", "/"),
            Arguments.of("/aaa", "/"),
            Arguments.of("/", null),
            Arguments.of(null, null)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("parentPathSource")
    public void testParentPath(String path, String expectedPath)
    {
        String actual = URIUtil.parentPath(path);
        assertEquals(expectedPath, actual, String.format("parent %s", path));
    }

    public static Stream<Arguments> equalsIgnoreEncodingStringTrueSource()
    {
        return Stream.of(
            Arguments.of("http://example.com/foo/bar", "http://example.com/foo/bar"),
            Arguments.of("/barry's", "/barry%27s"),
            Arguments.of("/barry%27s", "/barry's"),
            Arguments.of("/barry%27s", "/barry%27s"),
            Arguments.of("/b rry's", "/b%20rry%27s"),
            Arguments.of("/b rry%27s", "/b%20rry's"),
            Arguments.of("/b rry%27s", "/b%20rry%27s"),

            Arguments.of("/foo%2fbar", "/foo%2fbar"),
            Arguments.of("/foo%2fbar", "/foo%2Fbar"),

            // encoded vs not-encode ("%" symbol is encoded as "%25")
            Arguments.of("/abc%25xyz", "/abc%xyz"),
            Arguments.of("/abc%25xy", "/abc%xy"),
            Arguments.of("/abc%25x", "/abc%x"),
            Arguments.of("/zzz%25", "/zzz%")
        );
    }

    @ParameterizedTest
    @MethodSource("equalsIgnoreEncodingStringTrueSource")
    public void testEqualsIgnoreEncodingStringTrue(String uriA, String uriB)
    {
        assertTrue(URIUtil.equalsIgnoreEncodings(uriA, uriB));
    }

    public static Stream<Arguments> equalsIgnoreEncodingStringFalseSource()
    {
        return Stream.of(
            // case difference
            Arguments.of("ABC", "abc"),
            // Encoding difference ("'" is "%27")
            Arguments.of("/barry's", "/barry%26s"),
            // Never match on "%2f" differences - only intested in filename / directory name differences
            // This could be a directory called "foo" with a file called "bar" on the left, and just a file "foo%2fbar" on the right
            Arguments.of("/foo/bar", "/foo%2fbar"),
            // not actually encoded
            Arguments.of("/foo2fbar", "/foo/bar"),
            // encoded vs not-encode ("%" symbol is encoded as "%25")
            Arguments.of("/yyy%25zzz", "/aaa%xxx"),
            Arguments.of("/zzz%25", "/aaa%")
        );
    }

    @ParameterizedTest
    @MethodSource("equalsIgnoreEncodingStringFalseSource")
    public void testEqualsIgnoreEncodingStringFalse(String uriA, String uriB)
    {
        assertFalse(URIUtil.equalsIgnoreEncodings(uriA, uriB));
    }

    public static Stream<Arguments> equalsIgnoreEncodingURITrueSource()
    {
        return Stream.of(
            Arguments.of(
                URI.create("jar:file:/path/to/main.jar!/META-INF/versions/"),
                URI.create("jar:file:/path/to/main.jar!/META-INF/%76ersions/")
            ),
            Arguments.of(
                URI.create("JAR:FILE:/path/to/main.jar!/META-INF/versions/"),
                URI.create("jar:file:/path/to/main.jar!/META-INF/versions/")
            )
        );
    }

    @ParameterizedTest
    @MethodSource("equalsIgnoreEncodingURITrueSource")
    public void testEqualsIgnoreEncodingURITrue(URI uriA, URI uriB)
    {
        assertTrue(URIUtil.equalsIgnoreEncodings(uriA, uriB));
    }

    public static Stream<Arguments> getJarSourceStringSource()
    {
        return Stream.of(
            Arguments.of("file:///tmp/", "file:///tmp/"),
            Arguments.of("jar:file:///tmp/foo.jar", "file:///tmp/foo.jar"),
            Arguments.of("jar:file:///tmp/foo.jar!/some/path", "file:///tmp/foo.jar")
        );
    }

    @ParameterizedTest
    @MethodSource("getJarSourceStringSource")
    public void testJarSourceString(String uri, String expectedJarUri) throws Exception
    {
        assertThat(URIUtil.getJarSource(uri), is(expectedJarUri));
    }

    public static Stream<Arguments> getJarSourceURISource()
    {
        return Stream.of(
            Arguments.of(URI.create("file:///tmp/"), URI.create("file:///tmp/")),
            Arguments.of(URI.create("jar:file:///tmp/foo.jar"), URI.create("file:///tmp/foo.jar")),
            Arguments.of(URI.create("jar:file:///tmp/foo.jar!/some/path"), URI.create("file:///tmp/foo.jar"))
        );
    }

    @ParameterizedTest
    @MethodSource("getJarSourceURISource")
    public void testJarSourceURI(URI uri, URI expectedJarUri) throws Exception
    {
        assertThat(URIUtil.getJarSource(uri), is(expectedJarUri));
    }

    public static Stream<Arguments> encodeSpacesSource()
    {
        return Stream.of(
            // null
            Arguments.of(null, null),

            // no spaces
            Arguments.of("abc", "abc"),

            // match
            Arguments.of("a c", "a%20c"),
            Arguments.of("   ", "%20%20%20"),
            Arguments.of("a%20space", "a%20space")
        );
    }

    @ParameterizedTest
    @MethodSource("encodeSpacesSource")
    public void testEncodeSpaces(String raw, String expected)
    {
        assertThat(URIUtil.encodeSpaces(raw), is(expected));
    }

    public static Stream<Arguments> encodeSpecific()
    {
        return Stream.of(
            // [raw, chars, expected]

            // null input
            Arguments.of(null, null, null),

            // null chars
            Arguments.of("abc", null, "abc"),

            // empty chars
            Arguments.of("abc", "", "abc"),

            // no matches
            Arguments.of("abc", ".;", "abc"),
            Arguments.of("xyz", ".;", "xyz"),
            Arguments.of(":::", ".;", ":::"),

            // matches
            Arguments.of("a c", " ", "a%20c"),
            Arguments.of("name=value", "=", "name%3Dvalue"),
            Arguments.of("This has fewer then 10% hits.", ".%", "This has fewer then 10%25 hits%2E"),

            // partially encoded already
            Arguments.of("a%20name=value%20pair", "=", "a%20name%3Dvalue%20pair"),
            Arguments.of("a%20name=value%20pair", "=%", "a%2520name%3Dvalue%2520pair")
        );
    }

    @ParameterizedTest
    @MethodSource(value = "encodeSpecific")
    public void testEncodeSpecific(String raw, String chars, String expected)
    {
        assertThat(URIUtil.encodeSpecific(raw, chars), is(expected));
    }

    public static Stream<Arguments> decodeSpecific()
    {
        return Stream.of(
            // [raw, chars, expected]

            // null input
            Arguments.of(null, null, null),

            // null chars
            Arguments.of("abc", null, "abc"),

            // empty chars
            Arguments.of("abc", "", "abc"),

            // no matches
            Arguments.of("abc", ".;", "abc"),
            Arguments.of("xyz", ".;", "xyz"),
            Arguments.of(":::", ".;", ":::"),

            // matches
            Arguments.of("a%20c", " ", "a c"),
            Arguments.of("name%3Dvalue", "=", "name=value"),
            Arguments.of("This has fewer then 10%25 hits%2E", ".%", "This has fewer then 10% hits."),

            // partially decode
            Arguments.of("a%20name%3Dvalue%20pair", "=", "a%20name=value%20pair"),
            Arguments.of("a%2520name%3Dvalue%2520pair", "=%", "a%20name=value%20pair")
        );
    }

    @ParameterizedTest
    @MethodSource(value = "decodeSpecific")
    public void testDecodeSpecific(String raw, String chars, String expected)
    {
        assertThat(URIUtil.decodeSpecific(raw, chars), is(expected));
    }

    public static Stream<Arguments> resourceUriLastSegmentSource()
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        return Stream.of(
            Arguments.of("test.war", "test.war"),
            Arguments.of("a/b/c/test.war", "test.war"),
            Arguments.of("bar%2Fbaz/test.war", "test.war"),
            Arguments.of("fizz buzz/test.war", "test.war"),
            Arguments.of("another one/bites the dust/", "bites the dust"),
            Arguments.of("another+one/bites+the+dust/", "bites+the+dust"),
            Arguments.of("another%20one/bites%20the%20dust/", "bites%20the%20dust"),
            Arguments.of("spanish/n\u00FAmero.war", "n\u00FAmero.war"),
            Arguments.of("spanish/n%C3%BAmero.war", "n%C3%BAmero.war"),
            Arguments.of("a/b!/", "b"),
            Arguments.of("a/b!/c/", "b"),
            Arguments.of("a/b!/c/d/", "b"),
            Arguments.of("a/b%21/", "b%21")
        );
    }

    /**
     * Using FileSystem provided URIs, attempt to get last URI path segment
     */
    @ParameterizedTest
    @MethodSource("resourceUriLastSegmentSource")
    public void testFileUriGetUriLastPathSegment(String basePath, String expectedName) throws IOException
    {
        Path root = workDir.getPath();
        Path base = root.resolve(basePath);
        if (basePath.endsWith("/"))
        {
            FS.ensureDirExists(base);
        }
        else
        {
            FS.ensureDirExists(base.getParent());
            FS.touch(base);
        }
        URI uri = base.toUri();
        if (OS.MAC.isCurrentOs())
        {
            // Normalize Unicode to NFD form that OSX Path/FileSystem produces
            expectedName = Normalizer.normalize(expectedName, Normalizer.Form.NFD);
        }
        assertThat(URIUtil.getUriLastPathSegment(uri), is(expectedName));
    }

    public static Stream<Arguments> uriLastSegmentSource() throws URISyntaxException, IOException
    {
        final String TEST_RESOURCE_JAR = "test-base-resource.jar";
        Path testJar = MavenTestingUtils.getTestResourcePathFile(TEST_RESOURCE_JAR);
        URI uri = new URI("jar", testJar.toUri().toASCIIString(), null);

        Map<String, Object> env = new HashMap<>();
        env.put("multi-release", "runtime");

        List<Arguments> arguments = new ArrayList<>();
        arguments.add(Arguments.of(uri, TEST_RESOURCE_JAR));
        try (FileSystem zipFs = FileSystems.newFileSystem(uri, env))
        {
            FileVisitOption[] fileVisitOptions = new FileVisitOption[]{};

            for (Path root : zipFs.getRootDirectories())
            {
                try (Stream<Path> entryStream = Files.find(root, 10, (path, attrs) -> true, fileVisitOptions))
                {
                    entryStream.forEach((path) ->
                    {
                        if (path.toString().endsWith("!/"))
                        {
                            // skip - JAR entry type not supported by Jetty
                            // TODO: re-enable once we start to use zipfs
                            LOG.warn("Skipping Unsupported entry: " + path.toUri());
                        }
                        else
                        {
                            arguments.add(Arguments.of(path.toUri(), TEST_RESOURCE_JAR));
                        }
                    });
                }
            }
        }

        return arguments.stream();
    }

    /**
     * Tests of URIs last segment, including "jar:file:" based URIs.
     */
    @ParameterizedTest
    @MethodSource("uriLastSegmentSource")
    public void testGetUriLastPathSegment(URI uri, String expectedName)
    {
        assertThat(URIUtil.getUriLastPathSegment(uri), is(expectedName));
    }

    public static Stream<Arguments> addQueryParameterSource()
    {
        final String newQueryParam = "newParam=11";
        return Stream.of(
            Arguments.of(null, newQueryParam, is(newQueryParam)),
            Arguments.of(newQueryParam, null, is(newQueryParam)),
            Arguments.of("", newQueryParam, is(newQueryParam)),
            Arguments.of(newQueryParam, "", is(newQueryParam)),
            Arguments.of("existingParam=3", newQueryParam, is("existingParam=3&" + newQueryParam)),
            Arguments.of(newQueryParam, "existingParam=3", is(newQueryParam + "&existingParam=3")),
            Arguments.of("existingParam1=value1&existingParam2=value2", newQueryParam, is("existingParam1=value1&existingParam2=value2&" + newQueryParam)),
            Arguments.of(newQueryParam, "existingParam1=value1&existingParam2=value2", is(newQueryParam + "&existingParam1=value1&existingParam2=value2"))
        );
    }

    @ParameterizedTest
    @MethodSource("addQueryParameterSource")
    public void testAddQueryParam(String param1, String param2, Matcher<String> matcher)
    {
        assertThat(URIUtil.addQueries(param1, param2), matcher);
    }

    @Test
    public void testEncodeDecodeVisibleOnly()
    {
        StringBuilder builder = new StringBuilder();
        builder.append('/');
        for (char i = 0; i < 0x7FFF; i++)
            builder.append(i);
        String path = builder.toString();
        String encoded = URIUtil.encodePath(path);
        // Check endoded is visible
        for (char c : encoded.toCharArray())
        {
            assertTrue(c > 0x20 && c < 0x80);
            assertFalse(Character.isWhitespace(c));
            assertFalse(Character.isISOControl(c));
        }
        // check decode to original
        String decoded = URIUtil.decodePath(encoded);
        assertEquals(path, decoded);
    }
}
