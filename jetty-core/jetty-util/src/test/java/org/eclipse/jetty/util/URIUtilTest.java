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

package org.eclipse.jetty.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * URIUtil Tests.
 */
@SuppressWarnings("SpellCheckingInspection")
@ExtendWith(WorkDirExtension.class)
public class URIUtilTest
{
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
            Arguments.of("test?\u00f6?\u00f6:\u00df", "test%3F%C3%B6%3F%C3%B6:%C3%9F"),
            Arguments.of("/test space/", "/test%20space/"),
            Arguments.of("/test\u007fdel/", "/test%7Fdel/")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("encodePathSource")
    public void testEncodePath(String rawPath, String expectedEncoded)
    {
        // test basic encode/decode
        String result = URIUtil.encodePath(rawPath);
        assertEquals(expectedEncoded, result);
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
        return Stream.of(
            Arguments.of("/foo/bar", "/foo/bar", "/foo/bar"),

            // Simple encoding
            Arguments.of("/f%20%6f/b%20r", "/f%20o/b%20r", "/f o/b r"),

            // UTF8 and unicode handling
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
            Arguments.of("/foo/b\u00e4\u00e4", "/foo/bää", "/foo/bää"),
            Arguments.of("/f\u20ac\u20ac/bar", "/f€€/bar", "/f€€/bar"),
            Arguments.of("/f%d8%a9%D8%A9/bar", "/f\u0629\u0629/bar", "/f\u0629\u0629/bar"),

            // Encoded UTF-8 unicode (euro)
            Arguments.of("/f%e2%82%ac%E2%82%AC/bar", "/f€€/bar", "/f€€/bar"),

            // Encoded delimiters
            Arguments.of("/foo%2fbar", "/foo%2Fbar", "/foo/bar"),
            Arguments.of("/foo%252fbar", "/foo%252fbar", "/foo%2fbar"),
            Arguments.of("/foo%3bbar", "/foo%3Bbar", "/foo;bar"),
            Arguments.of("/foo%3fbar", "/foo%3Fbar", "/foo?bar"),

            // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
            Arguments.of("/f%20o/b%20r", "/f%20o/b%20r", "/f o/b r"),
            Arguments.of("f\u00e4\u00e4%2523%3b%2c:%3db%20a%20r%3D", "f\u00e4\u00e4%2523%3B,:=b%20a%20r=", "f\u00e4\u00e4%23;,:=b a r="),
            Arguments.of("f%d8%a9%D8%A9%2523%3b%2c:%3db%20a%20r", "f\u0629\u0629%2523%3B,:=b%20a%20r", "f\u0629\u0629%23;,:=b a r"),

            // path parameters should be ignored
            Arguments.of("/foo;ignore/bar;ignore", "/foo/bar", "/foo/bar"),
            Arguments.of("/f\u00e4\u00e4;ignore/bar;ignore", "/fää/bar", "/fää/bar"),
            Arguments.of("/f%d8%a9%d8%a9%2523;ignore/bar;ignore", "/f\u0629\u0629%2523/bar", "/f\u0629\u0629%23/bar"),
            Arguments.of("foo%2523%3b%2c:%3db%20a%20r;rubbish", "foo%2523%3B,:=b%20a%20r", "foo%23;,:=b a r"),

            // test for chars that are somehow already decoded, but shouldn't be
            Arguments.of("/foo bar\n", "/foo%20bar%0A", "/foo bar\n"),
            Arguments.of("/foo\u0000bar", "/foo%00bar", "/foo\u0000bar"),
            Arguments.of("/foo/bär", "/foo/bär", "/foo/bär"),
            Arguments.of("/foo/€/bar", "/foo/€/bar", "/foo/€/bar"),
            Arguments.of("/fo %2fo/b%61r", "/fo%20%2Fo/bar", "/fo /o/bar"),

            // Test for null character (real world ugly test case)
            Arguments.of("/%00/", "/%00/", "/\u0000/"),
            Arguments.of(null, null, null),

            // Deprecated Microsoft Percent-U encoding
            Arguments.of("abc%u3040", "abc\u3040", "abc\u3040"),

            // Invalid UTF-8 - replacement characters should be present on invalid sequences
            // URI paths do not support ISO-8859-1, so this should not be a fallback of our decodePath implementation
            /* TODO: remove ISO-8859-1 fallback mode in decodePath - Issue #9489
            Arguments.of("/a%D8%2fbar", "/a�%2Fbar", "/a�%2Fbar"), // invalid 2 octet sequence
            Arguments.of("/abc%C3%28", "/abc�", "/abc�"), // invalid 2 octet sequence
            Arguments.of("/abc%A0%A1", "/abc��", "/abc��"), // invalid 2 octet sequence
            Arguments.of("/abc%e2%28%a1", "/abc��", "/abc��"), // invalid 3 octet sequence
            Arguments.of("/abc%e2%82%28", "/abc�", "/abc�"), // invalid 3 octet sequence
            Arguments.of("/abc%f0%28%8c%bc", "/abc���", "/abc���"), // invalid 4 octet sequence
            Arguments.of("/abc%f0%90%28%bc", "/abc��", "/abc��"), // invalid 4 octet sequence
            Arguments.of("/abc%f0%28%8c%28", "/abc��(", "/abc��("), // invalid 4 octet sequence
            Arguments.of("/abc%f8%a1%a1%a1%a1", "/abc�����", "/abc�����"), // valid sequence, but not unicode
            Arguments.of("/abc%fc%a1%a1%a1%a1%a1", "/abc������", "/abc������"), // valid sequence, but not unicode
            Arguments.of("/abc%f8%a1%a1%a1", "/abc����", "/abc����"), // incomplete sequence
             */

            // Deprecated Microsoft Percent-U encoding
            Arguments.of("/abc%u3040", "/abc\u3040", "/abc\u3040"),

            // Canonical paths are also normalized
            Arguments.of("./bar", "bar", "./bar"),
            Arguments.of("/foo/./bar", "/foo/bar", "/foo/./bar"),
            Arguments.of("/foo/../bar", "/bar", "/foo/../bar"),
            Arguments.of("/foo/.../bar", "/foo/.../bar", "/foo/.../bar"),
            Arguments.of("/foo/%2e/bar", "/foo/bar", "/foo/./bar"), // Not by the RFC, but safer
            Arguments.of("/foo/%2e%2e/bar", "/bar", "/foo/../bar"), // Not by the RFC, but safer
            Arguments.of("/foo/%2e%2e%2e/bar", "/foo/.../bar", "/foo/.../bar")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("decodePathSource")
    public void testCanonicalEncodedPath(String encodedPath, String canonicalPath, String decodedPath)
    {
        String path = URIUtil.canonicalPath(encodedPath);
        assertEquals(canonicalPath, path);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("decodePathSource")
    public void testDecodePath(String encodedPath, String expectedCanonicalPath, String expectedDecodedPath)
    {
        String path = URIUtil.decodePath(encodedPath);
        assertEquals(expectedDecodedPath, path);
    }

    public static Stream<Arguments> decodeBadPathSource()
    {
        List<Arguments> arguments = new ArrayList<>();

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

    public static Stream<Arguments> uriAddPathEncodedSource()
    {
        List<Arguments> cases = new ArrayList<>();

        URI baseUri;

        baseUri = URI.create("file:///path/");

        cases.add(Arguments.of(baseUri, null, "file:///path/"));
        cases.add(Arguments.of(baseUri, "", "file:///path/"));
        cases.add(Arguments.of(baseUri, "/", "file:///path/"));
        cases.add(Arguments.of(baseUri, "bãm", "file:///path/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "file:///path/b%C3%A3m"));

        baseUri = URI.create("file:///tmp/aaa");

        cases.add(Arguments.of(baseUri, null, "file:///tmp/aaa"));
        cases.add(Arguments.of(baseUri, "", "file:///tmp/aaa"));
        cases.add(Arguments.of(baseUri, "/", "file:///tmp/aaa/"));
        cases.add(Arguments.of(baseUri, "bãm", "file:///tmp/aaa/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "file:///tmp/aaa/b%C3%A3m"));

        baseUri = URI.create("/");

        cases.add(Arguments.of(baseUri, null, "/"));
        cases.add(Arguments.of(baseUri, "", "/"));
        cases.add(Arguments.of(baseUri, "/", "/"));
        cases.add(Arguments.of(baseUri, "bãm", "/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "/b%C3%A3m"));

        baseUri = URI.create("");

        cases.add(Arguments.of(baseUri, null, ""));
        cases.add(Arguments.of(baseUri, "", ""));
        cases.add(Arguments.of(baseUri, "/", "/"));
        cases.add(Arguments.of(baseUri, "bãm", "b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "/b%C3%A3m"));

        baseUri = URI.create("aaa/");

        cases.add(Arguments.of(baseUri, null, "aaa/"));
        cases.add(Arguments.of(baseUri, "", "aaa/"));
        cases.add(Arguments.of(baseUri, "/", "aaa/"));
        cases.add(Arguments.of(baseUri, "bãm/zzz", "aaa/b%C3%A3m/zzz"));
        cases.add(Arguments.of(baseUri, "bãm", "aaa/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "aaa/b%C3%A3m"));

        baseUri = URI.create(";JS");

        cases.add(Arguments.of(baseUri, null, ";JS"));
        cases.add(Arguments.of(baseUri, "", ";JS"));
        cases.add(Arguments.of(baseUri, "/", ";JS/"));
        cases.add(Arguments.of(baseUri, "bãm", ";JS/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", ";JS/b%C3%A3m"));

        baseUri = URI.create("file:///path;JS");

        cases.add(Arguments.of(baseUri, null, "file:///path;JS"));
        cases.add(Arguments.of(baseUri, "", "file:///path;JS"));
        cases.add(Arguments.of(baseUri, "/", "file:///path;JS/"));
        cases.add(Arguments.of(baseUri, "bãm", "file:///path;JS/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "file:///path;JS/b%C3%A3m"));

        baseUri = URI.create("?A=1");

        cases.add(Arguments.of(baseUri, null, "?A=1"));
        cases.add(Arguments.of(baseUri, "", "?A=1"));
        cases.add(Arguments.of(baseUri, "/", "?A=1/"));
        cases.add(Arguments.of(baseUri, "bãm", "?A=1/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "?A=1/b%C3%A3m"));

        baseUri = URI.create("aaa?A=1");

        cases.add(Arguments.of(baseUri, null, "aaa?A=1"));
        cases.add(Arguments.of(baseUri, "", "aaa?A=1"));
        cases.add(Arguments.of(baseUri, "/", "aaa?A=1/"));
        cases.add(Arguments.of(baseUri, "bãm", "aaa?A=1/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "aaa?A=1/b%C3%A3m"));

        baseUri = URI.create("aaa/?A=1");

        cases.add(Arguments.of(baseUri, null, "aaa/?A=1"));
        cases.add(Arguments.of(baseUri, "", "aaa/?A=1"));
        cases.add(Arguments.of(baseUri, "/", "aaa/?A=1/"));
        cases.add(Arguments.of(baseUri, "bãm", "aaa/?A=1/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm", "aaa/?A=1/b%C3%A3m"));

        baseUri = URI.create("file:///path?A=1");

        cases.add(Arguments.of(baseUri, null, "file:///path?A=1"));
        cases.add(Arguments.of(baseUri, "", "file:///path?A=1"));
        cases.add(Arguments.of(baseUri, "/", "file:///path?A=1/"));
        cases.add(Arguments.of(baseUri, "bãm", "file:///path?A=1/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "bãm/", "file:///path?A=1/b%C3%A3m/"));
        cases.add(Arguments.of(baseUri, "/bãm", "file:///path?A=1/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/bãm/", "file:///path?A=1/b%C3%A3m/"));

        baseUri = URI.create("jar:file:///path/foo.jar!/");

        cases.add(Arguments.of(baseUri, null, "jar:file:///path/foo.jar!/"));
        cases.add(Arguments.of(baseUri, "", "jar:file:///path/foo.jar!/"));
        cases.add(Arguments.of(baseUri, "/", "jar:file:///path/foo.jar!/"));
        cases.add(Arguments.of(baseUri, "b%C3%A3m", "jar:file:///path/foo.jar!/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "b%C3%A3m/", "jar:file:///path/foo.jar!/b%C3%A3m/"));
        cases.add(Arguments.of(baseUri, "/b%C3%A3m", "jar:file:///path/foo.jar!/b%C3%A3m"));
        cases.add(Arguments.of(baseUri, "/b%C3%A3m/", "jar:file:///path/foo.jar!/b%C3%A3m/"));

        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} + {1}")
    @MethodSource("uriAddPathEncodedSource")
    public void testUriAddPathEncoded(URI baseUri, String path, String expectedUri)
    {
        URI actual = URIUtil.addPath(baseUri, path);
        assertThat(actual.toASCIIString(), is(expectedUri));
    }

    /**
     * Test to show how URIUtil.addPath(URI, String) retains the input String and does
     * not normalize away details that might be relevant.
     */
    @Test
    public void testAddPathNav()
    {
        URI uri = URI.create("file:////c:/");
        URI actual = URIUtil.addPath(uri, "foo/../bar");
        assertThat(actual.toASCIIString(), is("file:////c:/foo/../bar"));

        actual = URIUtil.addPath(uri, "foo/..%2fbar");
        // pct-u encoded will be capitalized
        assertThat(actual.toASCIIString(), is("file:////c:/foo/..%2Fbar"));

        // java.net.URI will encode `\\` to `%5C` per URI rules
        actual = URIUtil.addPath(uri, "foo/..\\bar");
        assertThat(actual.toASCIIString(), is("file:////c:/foo/..%5Cbar"));

        // Adding a "file" to a URI that ends in a "file", what should it do?
        actual = URIUtil.addPath(URI.create("file:///opt/foo.txt"), "bar.dat");
        assertThat(actual.toASCIIString(), is("file:///opt/foo.txt/bar.dat"));
    }

    public static Stream<Arguments> addPathQuerySource()
    {
        return Stream.of(
            Arguments.of("/path", null, "/path"),
            Arguments.of("/path", "", "/path"),
            Arguments.of("/path", "    ", "/path"),
            Arguments.of("/path", "a=b", "/path?a=b"),
            Arguments.of("/path?a=b", "x=y", "/path?a=b&x=y")
        );
    }

    @ParameterizedTest
    @MethodSource("addPathQuerySource")
    public void testAddPathQuery(String path, String query, String expected)
    {
        String actual = URIUtil.addPathQuery(path, query);
        assertThat(actual, is(expected));
    }

    public static Stream<Arguments> encodePathSafeEncodingSource()
    {
        return Stream.of(
            Arguments.of("/foo", "/foo"),
            Arguments.of("/barry's", "/barry's"),
            Arguments.of("/barry%27s", "/barry's"),
            Arguments.of("/section[42]", "/section%5B42%5D"),
            Arguments.of("/dir?", "/dir%3F"),
            Arguments.of("/dir#", "/dir%23"),
            // encode utf-8 unicode
            Arguments.of("/bãm/", "/b%C3%A3m/"),
            Arguments.of("/bä€ãm/", "/b%C3%A4%E2%82%AC%C3%A3m/"),
            // encode naked % to %25
            Arguments.of("/abc%x", "/abc%25x"),
            // encoded characters to leave as-is
            Arguments.of("/foo/%2F", "/foo/%2F"), // the path "/" symbol
            Arguments.of("/foo%5B1%5D", "/foo%5B1%5D"), // the "[" and "]" symbols
            Arguments.of("/bar%23", "/bar%23"), // hash "#" symbol
            // normalize hex codes
            Arguments.of("/b%c3%a4%e2%82%ac%c3%a3m/", "/b%C3%A4%E2%82%AC%C3%A3m/")
        );
    }

    @ParameterizedTest
    @MethodSource("encodePathSafeEncodingSource")
    public void testEncodePathSafeEncoding(String input, String expected)
    {
        assertThat(URIUtil.encodePathSafeEncoding(input), is(expected));
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

    public static Stream<Arguments> correctBadFileURICases()
    {
        return Stream.of(
            // Already valid URIs
            Arguments.of("file:///", "file:///"),
            Arguments.of("file:///foo.jar", "file:///foo.jar"),
            Arguments.of("jar:file:///foo.jar!/", "jar:file:///foo.jar!/"),
            Arguments.of("jar:file:///foo.jar!/zed.txt", "jar:file:///foo.jar!/zed.txt"),
            // Badly created File.toURL.toURI URIs
            Arguments.of("file:/", "file:///"),
            Arguments.of("file:/foo.jar", "file:///foo.jar"),
            Arguments.of("jar:file:/foo.jar", "jar:file:///foo.jar"),
            Arguments.of("jar:file:/foo.jar!/", "jar:file:///foo.jar!/"),
            Arguments.of("jar:file:/foo.jar!/zed.txt", "jar:file:///foo.jar!/zed.txt"),
            // Windows UNC uris
            Arguments.of("file://machine/share/foo.jar", "file://machine/share/foo.jar"),
            Arguments.of("file:////machine/share/foo.jar", "file:////machine/share/foo.jar"),
            // Excessive slashes (can't do anything)
            Arguments.of("file://////foo.jar", "file://////foo.jar"),
            // Not an absolute file uri
            Arguments.of("file:foo.jar", "file:foo.jar"),
            Arguments.of("foo.jar", "foo.jar"),
            Arguments.of("/foo.jar", "/foo.jar"),
            // Not a file or jar uri
            Arguments.of("https://webtide.com", "https://webtide.com"),
            Arguments.of("mailto:jesse@webtide.com", "mailto:jesse@webtide.com"),
            // Empty scheme
            // Arguments.of("file:", "file:"), java.net.URI requires an SSP for `file`
            Arguments.of("jar:file:", "jar:file:")
        );
    }

    @ParameterizedTest
    @MethodSource("correctBadFileURICases")
    public void testCorrectURI(String input, String expected)
    {
        URI inputUri = URI.create(input);
        URI actualUri = URIUtil.correctURI(inputUri);
        URI expectedUri = URI.create(expected);
        assertThat(actualUri.toASCIIString(), is(expectedUri.toASCIIString()));
    }

    @Test
    public void testCorrectBadFileURIActualFile() throws Exception
    {
        Path testDir = workDir.getEmptyPathDir();
        Path testfile = testDir.resolve("testCorrectBadFileURIActualFile.txt");
        FS.touch(testfile);

        URI expectedUri = testfile.toUri(); // correct URI with `://`

        assertThat(expectedUri.toASCIIString(), containsString("://"));

        File file = testfile.toFile();
        URI fileUri = file.toURI(); // java produced bad format with only `:/` (not `://`)
        URI fileUrlUri = file.toURL().toURI(); // java produced bad format with only `:/` (not `://`)

        // If these 2 tests start failing, that means Java itself has been fixed
        assertThat(fileUri.toASCIIString(), not(containsString("://")));
        assertThat(fileUrlUri.toASCIIString(), not(containsString("://")));

        assertThat(URIUtil.correctURI(fileUri).toASCIIString(), is(expectedUri.toASCIIString()));
        assertThat(URIUtil.correctURI(fileUrlUri).toASCIIString(), is(expectedUri.toASCIIString()));
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
        Path root = workDir.getEmptyPathDir();
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

        try
        {
            assertThat(URIUtil.getUriLastPathSegment(uri), is(expectedName));
        }
        catch (AssertionError e)
        {
            if (OS.MAC.isCurrentOs())
            {
                // Normalize Unicode to NFD form that OSX Path/FileSystem produces
                expectedName = Normalizer.normalize(expectedName, Normalizer.Form.NFD);
                assertThat(URIUtil.getUriLastPathSegment(uri), is(expectedName));
            }
            else
            {
                throw e;
            }
        }
    }

    public static Stream<Arguments> uriLastSegmentSource() throws IOException
    {
        final String TEST_RESOURCE_JAR = "test-base-resource.jar";
        List<Arguments> arguments = new ArrayList<>();
        Path testJar = MavenTestingUtils.getTestResourcePathFile(TEST_RESOURCE_JAR);
        URI jarFileUri = URIUtil.toJarFileUri(testJar.toUri());

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            arguments.add(Arguments.of(jarFileUri, TEST_RESOURCE_JAR));

            Path root = resourceFactory.newResource(jarFileUri).getPath();

            try (Stream<Path> entryStream = Files.find(root, 10, (path, attrs) -> true))
            {
                entryStream.filter(Files::isRegularFile)
                    .forEach((path) ->
                        arguments.add(Arguments.of(path.toUri(), TEST_RESOURCE_JAR)));
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
        // Check encoded is visible
        for (char c : encoded.toCharArray())
        {
            assertTrue(c > 0x20 && c < 0x7f);
            assertFalse(Character.isWhitespace(c));
            assertFalse(Character.isISOControl(c), "isISOControl(0x%2x)".formatted((byte)c));
        }
        // check decode to original
        String decoded = URIUtil.decodePath(encoded);
        assertEquals(path, decoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "a",
        "deadbeef",
        "321zzz123",
        "pct%25encoded",
        "a,b,c",
        "*",
        "_-_-_",
        "192.168.1.22",
        "192.168.1.com"
    })
    public void testIsValidHostRegisteredNameTrue(String token)
    {
        assertTrue(URIUtil.isValidHostRegisteredName(token), "Token [" + token + "] should be a valid reg-name");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " ",
        "tab\tchar",
        "a long name with spaces",
        "8-bit-\u00dd", // 8-bit characters
        "пример.рф", // unicode - raw IDN (not normalized to punycode)
        // Invalid pct-encoding
        "%XX",
        "%%",
        "abc%d",
        "100%",
        "[brackets]"
    })
    public void testIsValidHostRegisteredNameFalse(String token)
    {
        assertFalse(URIUtil.isValidHostRegisteredName(token), "Token [" + token + "] should be an invalid reg-name");
    }

    public static Stream<Arguments> uriJarPrefixCasesGood()
    {
        return Stream.of(
            Arguments.of("file:///opt/foo.jar", "!/zed.dat", "jar:file:///opt/foo.jar!/zed.dat"),
            Arguments.of("jar:file:///opt/foo.jar", "!/zed.dat", "jar:file:///opt/foo.jar!/zed.dat"),
            Arguments.of("jar:file:///opt/foo.jar!/something.txt", "!/zed.dat", "jar:file:///opt/foo.jar!/zed.dat")
        );
    }

    @ParameterizedTest
    @MethodSource("uriJarPrefixCasesGood")
    public void testUriJarPrefixGood(String input, String encodedSuffix, String expected)
    {
        URI inputURI = URI.create(input);
        URI outputURI = URIUtil.uriJarPrefix(inputURI, encodedSuffix);
        assertThat(outputURI.toASCIIString(), is(expected));
    }

    public static Stream<Arguments> uriJarPrefixCasesBad()
    {
        return Stream.of(
            // Not jar or file scheme
            Arguments.of("http://webtide.com", "!/zed.dat"),
            // null parameters
            Arguments.of(null, "!/zed.dat"),
            Arguments.of("jar:file:///opt/foo.jar!/something.txt", null),
            // empty encodedSuffix
            Arguments.of("file:///opt/foo.jar", ""),
            Arguments.of("jar:file:///opt/foo.jar", ""),
            Arguments.of("jar:file:///opt/foo.jar!/something.txt", ""),
            // encodedSuffix not starting with "!/"
            Arguments.of("file:///opt/foo.jar", "/zed.dat"),
            Arguments.of("jar:file:///opt/foo.jar", "/zed.dat"),
            Arguments.of("jar:file:///opt/foo.jar!/something.txt", "/zed.dat"),
            Arguments.of("file:///opt/foo.jar", "zed.dat"),
            Arguments.of("jar:file:///opt/foo.jar", "zed.dat"),
            Arguments.of("jar:file:///opt/foo.jar!/something.txt", "zed.dat")
        );
    }

    @ParameterizedTest
    @MethodSource("uriJarPrefixCasesBad")
    public void testUriJarPrefixBad(String input, String suffix)
    {
        final URI inputURI = input == null ? null : URI.create(input);
        assertThrows(IllegalArgumentException.class, () -> URIUtil.uriJarPrefix(inputURI, suffix));
    }

    public static Stream<Arguments> jarFileUriCases()
    {
        List<Arguments> cases = new ArrayList<>();

        String expected = "jar:file:/path/company-1.0.jar!/";
        cases.add(Arguments.of("file:/path/company-1.0.jar", expected));
        cases.add(Arguments.of("jar:file:/path/company-1.0.jar", expected));
        cases.add(Arguments.of("jar:file:/path/company-1.0.jar!/", expected));
        cases.add(Arguments.of("jar:file:/path/company-1.0.jar!/META-INF/services", expected + "META-INF/services"));

        expected = "jar:file:/opt/jetty/webapps/app.war!/";
        cases.add(Arguments.of("file:/opt/jetty/webapps/app.war", expected));
        cases.add(Arguments.of("jar:file:/opt/jetty/webapps/app.war", expected));
        cases.add(Arguments.of("jar:file:/opt/jetty/webapps/app.war!/", expected));
        cases.add(Arguments.of("jar:file:/opt/jetty/webapps/app.war!/WEB-INF/classes", expected + "WEB-INF/classes"));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("jarFileUriCases")
    public void testToJarFileUri(String inputRawUri, String expectedRawUri)
    {
        URI actual = URIUtil.toJarFileUri(URI.create(inputRawUri));
        assertNotNull(actual);
        assertThat(actual.toASCIIString(), is(expectedRawUri));
    }

    public static Stream<Arguments> unwrapContainerCases()
    {
        List<Arguments> args = new ArrayList<>();

        if (OS.WINDOWS.isCurrentOs())
        {
            // Windows format (absolute and relative)
            args.add(Arguments.of("C:\\path\\to\\foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("D:\\path\\to\\bogus.txt", "file:///D:/path/to/bogus.txt"));
            args.add(Arguments.of("\\path\\to\\foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("\\path\\to\\bogus.txt", "file:///C:/path/to/bogus.txt"));
            // unix format (relative)
            args.add(Arguments.of("C:/path/to/foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("D:/path/to/bogus.txt", "file:///D:/path/to/bogus.txt"));
            args.add(Arguments.of("/path/to/foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("/path/to/bogus.txt", "file:///C:/path/to/bogus.txt"));
            // URI format (absolute)
            args.add(Arguments.of("file:///D:/path/to/zed.jar", "file:///D:/path/to/zed.jar"));
            args.add(Arguments.of("jar:file:///E:/path/to/bar.jar!/internal.txt", "file:///E:/path/to/bar.jar"));
        }
        else
        {
            // URI (and unix) format (relative)
            args.add(Arguments.of("/path/to/foo.jar", "file:///path/to/foo.jar"));
            args.add(Arguments.of("/path/to/bogus.txt", "file:///path/to/bogus.txt"));
        }
        // URI format (absolute)
        args.add(Arguments.of("file:///path/to/zed.jar", "file:///path/to/zed.jar"));
        args.add(Arguments.of("jar:file:///path/to/bar.jar!/internal.txt", "file:///path/to/bar.jar"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("unwrapContainerCases")
    public void testUnwrapContainer(String inputRawUri, String expected)
    {
        URI input = URIUtil.toURI(inputRawUri);
        URI actual = URIUtil.unwrapContainer(input);
        assertThat(actual.toASCIIString(), is(expected));
    }

    public static Stream<Arguments> toURICases()
    {
        List<Arguments> args = new ArrayList<>();

        if (OS.WINDOWS.isCurrentOs())
        {
            // Windows format (absolute and relative)
            args.add(Arguments.of("C:\\path\\to\\foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("D:\\path\\to\\bogus.txt", "file:///D:/path/to/bogus.txt"));
            args.add(Arguments.of("\\path\\to\\foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("\\path\\to\\bogus.txt", "file:///C:/path/to/bogus.txt"));
            // unix format (relative)
            args.add(Arguments.of("C:/path/to/foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("D:/path/to/bogus.txt", "file:///D:/path/to/bogus.txt"));
            args.add(Arguments.of("/path/to/foo.jar", "file:///C:/path/to/foo.jar"));
            args.add(Arguments.of("/path/to/bogus.txt", "file:///C:/path/to/bogus.txt"));
            // URI format (absolute)
            args.add(Arguments.of("file:///D:/path/to/zed.jar", "file:///D:/path/to/zed.jar"));
            args.add(Arguments.of("file:/e:/zed/yotta.txt", "file:///e:/zed/yotta.txt"));
            args.add(Arguments.of("jar:file:///E:/path/to/bar.jar", "jar:file:///E:/path/to/bar.jar"));
        }
        else
        {
            // URI (and unix) format (relative)
            args.add(Arguments.of("/path/to/foo.jar", "file:///path/to/foo.jar"));
            args.add(Arguments.of("/path/to/bogus.txt", "file:///path/to/bogus.txt"));
        }
        // URI format (absolute)
        args.add(Arguments.of("file:///path/to/zed.jar", "file:///path/to/zed.jar"));
        args.add(Arguments.of("jar:file:///path/to/bar.jar", "jar:file:///path/to/bar.jar"));

        return args.stream();
    }

    @ParameterizedTest
    @MethodSource("toURICases")
    public void testToURI(String inputRaw, String expectedUri)
    {
        URI actual = URIUtil.toURI(inputRaw);
        URI expected = URI.create(expectedUri);
        assertEquals(expected, actual);
    }

    @Test
    public void testSplitSingleJar()
    {
        // Bad java file.uri syntax
        String input = "file:/home/user/lib/acme.jar";
        List<URI> uris = URIUtil.split(input);
        // As zipfs with corrected file.uri syntax as well
        String expected = "jar:file:///home/user/lib/acme.jar!/";
        assertThat(uris.get(0).toString(), is(expected));
    }

    @Test
    public void testSplitSinglePath()
    {
        String input = "/home/user/lib/acme.jar";
        Path path = Path.of(input);
        List<URI> uris = URIUtil.split(input);
        String expected = String.format("jar:%s!/", path.toUri().toASCIIString());
        assertThat(uris.get(0).toString(), is(expected));
    }

    @Test
    public void testSplitOnComma()
    {
        Path base = workDir.getEmptyPathDir();
        Path dir = base.resolve("dir");
        FS.ensureDirExists(dir);
        Path foo = dir.resolve("foo");
        FS.ensureDirExists(foo);
        Path bar = dir.resolve("bar");
        FS.ensureDirExists(bar);

        // This represents the user-space raw configuration
        String config = String.format("%s,%s,%s", dir, foo, bar);

        // Split using commas
        List<URI> uris = URIUtil.split(config);

        URI[] expected = new URI[] {
            dir.toUri(),
            foo.toUri(),
            bar.toUri()
        };
        assertThat(uris, contains(expected));
    }

    @Test
    public void testSplitOnPipe()
    {
        Path base = workDir.getEmptyPathDir();
        Path dir = base.resolve("dir");
        FS.ensureDirExists(dir);
        Path foo = dir.resolve("foo");
        FS.ensureDirExists(foo);
        Path bar = dir.resolve("bar");
        FS.ensureDirExists(bar);

        // This represents the user-space raw configuration
        String config = String.format("%s|%s|%s", dir, foo, bar);

        // Split using commas
        List<URI> uris = URIUtil.split(config);

        URI[] expected = new URI[] {
            dir.toUri(),
            foo.toUri(),
            bar.toUri()
        };
        assertThat(uris, contains(expected));
    }

    @Test
    public void testSplitOnSemicolon()
    {
        Path base = workDir.getEmptyPathDir();
        Path dir = base.resolve("dir");
        FS.ensureDirExists(dir);
        Path foo = dir.resolve("foo");
        FS.ensureDirExists(foo);
        Path bar = dir.resolve("bar");
        FS.ensureDirExists(bar);

        // This represents the user-space raw configuration
        String config = String.format("%s;%s;%s", dir, foo, bar);

        // Split using commas
        List<URI> uris = URIUtil.split(config);

        URI[] expected = new URI[] {
            dir.toUri(),
            foo.toUri(),
            bar.toUri()
        };
        assertThat(uris, contains(expected));
    }

    @Test
    public void testSplitOnPipeWithGlob() throws IOException
    {
        Path base = workDir.getEmptyPathDir();
        Path dir = base.resolve("dir");
        FS.ensureDirExists(dir);
        Path foo = dir.resolve("foo");
        FS.ensureDirExists(foo);
        Path bar = dir.resolve("bar");
        FS.ensureDirExists(bar);
        FS.touch(bar.resolve("lib-foo.jar"));
        FS.touch(bar.resolve("lib-zed.zip"));

        // This represents the user-space raw configuration with a glob
        String config = String.format("%s;%s;%s%s*", dir, foo, bar, File.separator);

        // Split using commas
        List<URI> uris = URIUtil.split(config);

        URI[] expected = new URI[] {
            dir.toUri(),
            foo.toUri(),
            // Should see the two archives as `jar:file:` URI entries
            URIUtil.toJarFileUri(bar.resolve("lib-foo.jar").toUri()),
            URIUtil.toJarFileUri(bar.resolve("lib-zed.zip").toUri())
        };
        assertThat(uris, contains(expected));
    }

    public static Stream<Arguments> schemeHostPortShortCases()
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
    @MethodSource("schemeHostPortShortCases")
    public void testAppendSchemeHostPortBuilder(String scheme, String server, int port, String expectedStr)
    {
        StringBuilder actual = new StringBuilder();
        URIUtil.appendSchemeHostPort(actual, scheme, server, port);
        assertEquals(expectedStr, actual.toString());
    }

    @ParameterizedTest
    @MethodSource("schemeHostPortShortCases")
    public void testAppendSchemeHostPortBuffer(String scheme, String server, int port, String expectedStr)
    {
        StringBuffer actual = new StringBuffer();
        URIUtil.appendSchemeHostPort(actual, scheme, server, port);
        assertEquals(expectedStr, actual.toString());
    }

    public static List<Arguments> getNewURICases()
    {
        List<Arguments> cases = new ArrayList<>();

        cases.addAll(List.of(
            // Default behaviors of stripping a port number based on scheme
            // Query specified
            Arguments.of("http", "example.org", 0, "/", "a=b", null, "http://example.org/?a=b"),
            Arguments.of("http", "example.org", 0, "/documentation/latest/", "a=b", null, "http://example.org/documentation/latest/?a=b"),
            Arguments.of("http", "example.org", 0, null, "a=b", null, "http://example.org/?a=b"),
            Arguments.of("http", "example.org", 0, null, "", null, "http://example.org/?")
        ));
        return cases;
    }

    public static List<Arguments> schemeHostPortCases()
    {
        return List.of(
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
            Arguments.of("ftp", "example.org", 2121, "ftp://example.org:2121")
        );
    }

    public static List<Arguments> schemeHostPortPathCases()
    {
        List<Arguments> cases = new ArrayList<>();

        cases.addAll(List.of(
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
            // Query specified
            Arguments.of("http", "example.org", 0, "/", "a=b", null, "http://example.org/?a=b"),
            Arguments.of("http", "example.org", 0, "/documentation/latest/", "a=b", null, "http://example.org/documentation/latest/?a=b"),
            Arguments.of("http", "example.org", 0, null, "a=b", null, "http://example.org/?a=b"),
            Arguments.of("http", "example.org", 0, null, "", null, "http://example.org/?")
        ));
        return cases;
    }

    public static List<Arguments> schemeHostPortFragmentCases()
    {
        List<Arguments> cases = new ArrayList<>();
        cases.addAll(schemeHostPortPathCases());

        cases.addAll(List.of(
            // Fragment specified
            Arguments.of("http", "example.org", 0, "/", null, "", "http://example.org/#"),
            Arguments.of("http", "example.org", 0, "/", null, "toc", "http://example.org/#toc"),
            Arguments.of("http", "example.org", 0, null, null, "toc", "http://example.org/#toc"),
            // Empty query & fragment - behavior matches java URI and URL
            Arguments.of("http", "example.org", 0, null, "", "", "http://example.org/?#")
        ));

        return cases;
    }

    @ParameterizedTest
    @MethodSource("schemeHostPortCases")
    public void testNewURIShort(String scheme, String server, int port, String expectedStr)
    {
        String actual = URIUtil.newURI(scheme, server, port);
        assertEquals(expectedStr, actual.toString());
    }

    @ParameterizedTest
    @MethodSource("schemeHostPortPathCases")
    public void testNewURI(String scheme, String server, int port, String path, String query, String fragment, String expectedStr)
    {
        assumeTrue(StringUtil.isBlank(fragment), "Skip tests with fragments, as this newURI doesn't have them");
        String actual = URIUtil.newURI(scheme, server, port, path, query);
        assertEquals(expectedStr, actual.toString());
    }

    @ParameterizedTest
    @MethodSource("schemeHostPortFragmentCases")
    public void testNewURIFragment(String scheme, String server, int port, String path, String query, String fragment, String expectedStr)
    {
        String actual = URIUtil.newURI(scheme, server, port, path, query, fragment);
        assertEquals(expectedStr, actual.toString());
    }

    @ParameterizedTest
    @CsvSource(value = {
        "http,80",
        "https,443",
        "ws,80",
        "wss,443",
        "ssh,22",
        "file,-1",
        "bundle,-1",
        "HTTP,80",
        "HttPs,443",
        "http+ssl,-1"
    })
    public void testGetDefaultPortForScheme(String scheme, int expectedPort)
    {
        int actual = URIUtil.getDefaultPortForScheme(scheme);
        assertEquals(expectedPort, actual);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "http,80,0",
        "https,443,0",
        "https,8443,8443",
        "ws,80,0",
        "ws,9999,9999",
        "wss,443,0",
        "wss,-1,0",
        "wss,0,0",
        "ssh,22,0",
        "file,-1,0",
        "bundle,-1,0",
        "HTTP,80,0",
        "HttPs,443,0",
        "http+ssl,-1,0"
    })
    public void testNormalizePortForScheme(String scheme, int port, int expectedPort)
    {
        int actual = URIUtil.normalizePortForScheme(scheme, port);
        assertEquals(expectedPort, actual);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "http,http",
        "https,https",
        "HTTP,http",
        "WSS,wss",
        "WS,ws",
        "XRTP,xrtp",
        "Https,https"
    })
    public void testNormalizeScheme(String input, String expected)
    {
        String actual = URIUtil.normalizeScheme(input);
        assertThat(actual, is(expected));
    }
}
