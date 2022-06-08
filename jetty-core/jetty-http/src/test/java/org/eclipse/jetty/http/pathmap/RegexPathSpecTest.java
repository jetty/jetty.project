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

package org.eclipse.jetty.http.pathmap;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RegexPathSpecTest
{
    public static void assertMatches(PathSpec spec, String path)
    {
        String msg = String.format("Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertNotNull(spec.matched(path), msg);
    }

    public static void assertNotMatches(PathSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertNull(spec.matched(path), msg);
    }

    @Test
    public void testExactSpec()
    {
        RegexPathSpec spec = new RegexPathSpec("^/a$");
        assertEquals("^/a$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(1, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.EXACT, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a");

        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/a/");

        assertThat(spec.getPathMatch("/a"), equalTo("/a"));
        assertThat(spec.getPathInfo("/a"), nullValue());
    }

    @Test
    public void testMiddleSpec()
    {
        RegexPathSpec spec = new RegexPathSpec("^/rest/([^/]*)/list$");
        assertEquals("^/rest/([^/]*)/list$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/rest/([^/]*)/list$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(3, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.MIDDLE_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/rest/api/list");
        assertMatches(spec, "/rest/1.0/list");
        assertMatches(spec, "/rest/2.0/list");
        assertMatches(spec, "/rest/accounts/list");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/rest/admin/delete");
        assertNotMatches(spec, "/rest/list");

        assertThat(spec.getPathMatch("/rest/1.0/list"), equalTo("/rest"));
        assertThat(spec.getPathInfo("/rest/1.0/list"), equalTo("/1.0/list"));
    }

    public static Stream<Arguments> matchedPathCases()
    {
        return Stream.of(
            // Suffix (with optional capture group)
            Arguments.of("^/[Tt]est(/.*)?$", "/test/info", "/test", "/info"),
            Arguments.of("^/[Tt]est(/.*)?$", "/test", "/test", null),
            Arguments.of("^(.*).do$", "/a.do", "", "/a.do"),
            Arguments.of("^(.*).do$", "/a/b/c.do", "", "/a/b/c.do"),
            Arguments.of("^(.*).do$", "/abcde.do", "", "/abcde.do"),
            // Exact (no capture group)
            Arguments.of("^/test/info$", "/test/info", "/test/info", null),
            // Middle (with one capture group)
            Arguments.of("^/rest/([^/]*)/list$", "/rest/api/list", "/rest", "/api/list"),
            Arguments.of("^/rest/([^/]*)/list$", "/rest/1.0/list", "/rest", "/1.0/list"),
            // Middle (with two capture groups)
            Arguments.of("^/t(.*)/i(.*)$", "/test/info", "/test/info", null),
            // Prefix (with optional capture group)
            Arguments.of("^/test(/.*)?$", "/test/info", "/test", "/info"),
            Arguments.of("^/a/(.*)?$", "/a/b/c/d", "/a", "/b/c/d"),
            // Prefix (with many capture groups)
            Arguments.of("^/test(/i.*)(/c.*)(/d.*)?$", "/test/info/code", "/test/info/code", null),
            Arguments.of("^/test(/i.*)(/c.*)(/d.*)?$", "/test/info/code/data", "/test/info/code/data", null),
            Arguments.of("^/test(/i.*)(/c.*)(/d.*)?$", "/test/ice/cream/dip", "/test/ice/cream/dip", null),
            // Suffix (with only 1 named capture group of id "name")
            Arguments.of("^(?<name>\\/.*)/.*\\.do$", "/test/info/code.do", "/test/info", "/code.do"),
            Arguments.of("^(?<name>\\/.*)/.*\\.do$", "/a/b/c/d/e/f/g.do", "/a/b/c/d/e/f", "/g.do"),
            // Suffix (with only 1 named capture group of id "info")
            Arguments.of("^/.*(?<info>\\/.*\\.do)$", "/test/info/code.do", "/test/info", "/code.do"),
            Arguments.of("^/.*(?<info>\\/.*\\.do)$", "/a/b/c/d/e/f/g.do", "/a/b/c/d/e/f", "/g.do"),
            // Middle (with 2 named capture groups)
            // this is pretty much an all glob signature
            Arguments.of("^(?<name>\\/.*)(?<info>\\/.*\\.action)$", "/test/info/code.action", "/test/info", "/code.action"),
            Arguments.of("^(?<name>\\/.*)(?<info>\\/.*\\.action)$", "/a/b/c/d/e/f/g.action", "/a/b/c/d/e/f", "/g.action"),
            // Named groups with gap in the middle
            Arguments.of("^(?<name>\\/.*)/x/(?<info>.*\\.action)$", "/a/b/c/x/e/f/g.action", "/a/b/c", "e/f/g.action"),
            // Named groups in opposite order
            Arguments.of("^(?<info>\\/.*)/x/(?<name>.*\\.action)$", "/a/b/c/x/e/f/g.action", "e/f/g.action", "/a/b/c")
        );
    }

    @ParameterizedTest(name = "[{index}] RegexPathSpec(\"{0}\").matched(\"{1}\")")
    @MethodSource("matchedPathCases")
    public void testMatchedPath(String regex, String input, String expectedPathMatch, String expectedPathInfo)
    {
        RegexPathSpec spec = new RegexPathSpec(regex);
        MatchedPath matched = spec.matched(input);
        assertEquals(expectedPathMatch, matched.getPathMatch(),
            String.format("RegexPathSpec(\"%s\").matched(\"%s\").getPathMatch()", regex, input));
        assertEquals(expectedPathInfo, matched.getPathInfo(),
            String.format("RegexPathSpec(\"%s\").matched(\"%s\").getPathInfo()", regex, input));
    }

    @Test
    public void testMiddleSpecNoGrouping()
    {
        RegexPathSpec spec = new RegexPathSpec("^/rest/[^/]+/list$");
        assertEquals("^/rest/[^/]+/list$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/rest/[^/]+/list$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(3, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.MIDDLE_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/rest/api/list");
        assertMatches(spec, "/rest/1.0/list");
        assertMatches(spec, "/rest/2.0/list");
        assertMatches(spec, "/rest/accounts/list");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/rest/admin/delete");
        assertNotMatches(spec, "/rest/list");

        assertThat(spec.getPathMatch("/rest/1.0/list"), equalTo("/rest/1.0/list"));
        assertThat(spec.getPathInfo("/rest/1.0/list"), nullValue());
    }

    @Test
    public void testPrefixSpec()
    {
        RegexPathSpec spec = new RegexPathSpec("^/a/(.*)$");
        assertEquals("^/a/(.*)$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a/(.*)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.PREFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a/");
        assertMatches(spec, "/a/b");
        assertMatches(spec, "/a/b/c/d/e");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");

        assertThat(spec.getPathMatch("/a/b/c/d/e"), equalTo("/a"));
        assertThat(spec.getPathInfo("/b/c/d/e"), nullValue());
    }

    @Test
    public void testSuffixSpecTraditional()
    {
        RegexPathSpec spec = new RegexPathSpec("^(.*).do$");
        assertEquals("^(.*).do$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^(.*).do$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(0, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a.do");
        assertMatches(spec, "/a/b/c.do");
        assertMatches(spec, "/abcde.do");
        assertMatches(spec, "/abc/efg.do");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/aa/bb.do/more");

        assertThat(spec.getPathMatch("/a/b/c.do"), equalTo("/a/b/c.do"));
        assertThat(spec.getPathInfo("/a/b/c.do"), nullValue());
    }

    /**
     * A suffix type path spec, where the beginning of the path is evaluated
     * but the rest of the path is ignored.
     * The beginning is starts with a glob, contains a literal, and no terminal "$".
     */
    @Test
    public void testSuffixSpecGlobish()
    {
        RegexPathSpec spec = new RegexPathSpec("^/[Hh]ello");
        assertEquals("^/[Hh]ello", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/[Hh]ello", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(1, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/hello");
        assertMatches(spec, "/Hello");

        assertNotMatches(spec, "/Hello/World");
        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/aa/bb.do/more");

        assertThat(spec.getPathMatch("/hello"), equalTo("/hello"));
        assertThat(spec.getPathInfo("/hello"), nullValue());

        assertThat(spec.getPathMatch("/Hello"), equalTo("/Hello"));
        assertThat(spec.getPathInfo("/Hello"), nullValue());

        MatchedPath matchedPath = spec.matched("/hello");
        assertThat(matchedPath.getPathMatch(), equalTo("/hello"));
        assertThat(matchedPath.getPathInfo(), nullValue());

        matchedPath = spec.matched("/Hello");
        assertThat(matchedPath.getPathMatch(), equalTo("/Hello"));
        assertThat(matchedPath.getPathInfo(), nullValue());
    }

    @Test
    public void testSuffixSpecMiddle()
    {
        RegexPathSpec spec = new RegexPathSpec("^.*/middle/.*$");
        assertEquals("^.*/middle/.*$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^.*/middle/.*$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a/middle/c.do");
        assertMatches(spec, "/a/b/c/d/middle/e/f");
        assertMatches(spec, "/middle/");

        assertNotMatches(spec, "/a.do");
        assertNotMatches(spec, "/a/middle");
        assertNotMatches(spec, "/middle");
    }

    @Test
    public void testSuffixSpecMiddleWithGroupings()
    {
        RegexPathSpec spec = new RegexPathSpec("^(.*)/middle/(.*)$");
        assertEquals("^(.*)/middle/(.*)$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^(.*)/middle/(.*)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a/middle/c.do");
        assertMatches(spec, "/a/b/c/d/middle/e/f");
        assertMatches(spec, "/middle/");

        assertNotMatches(spec, "/a.do");
        assertNotMatches(spec, "/a/middle");
        assertNotMatches(spec, "/middle");
    }

    @Test
    public void testNamedRegexGroup()
    {
        RegexPathSpec spec = new RegexPathSpec("^(?<name>(.*)/middle/)(?<info>.*)$");
        assertEquals("^(?<name>(.*)/middle/)(?<info>.*)$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^(?<name>(.*)/middle/)(?<info>.*)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a/middle/c.do");
        assertMatches(spec, "/a/b/c/d/middle/e/f");
        assertMatches(spec, "/middle/");

        assertNotMatches(spec, "/a.do");
        assertNotMatches(spec, "/a/middle");
        assertNotMatches(spec, "/middle");

        MatchedPath matchedPath = spec.matched("/a/middle/c.do");
        assertThat(matchedPath.getPathMatch(), is("/a/middle/"));
        assertThat(matchedPath.getPathInfo(), is("c.do"));
    }

    @Test
    public void testEquals()
    {
        assertThat(new RegexPathSpec("^(.*).do$"), equalTo(new RegexPathSpec("^(.*).do$")));
        assertThat(new RegexPathSpec("/foo"), equalTo(new RegexPathSpec("/foo")));
        assertThat(new RegexPathSpec("^(.*).do$"), not(equalTo(new RegexPathSpec("^(.*).gz$"))));
        assertThat(new RegexPathSpec("^(.*).do$"), not(equalTo(new RegexPathSpec("^.*.do$"))));
        assertThat(new RegexPathSpec("/foo"), not(equalTo(new ServletPathSpec("/foo"))));
    }
}
