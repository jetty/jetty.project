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

package org.eclipse.jetty.http.pathmap;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// @checkstyle-disable-check :  AvoidEscapedUnicodeCharactersCheck
public class PathMappingsTest
{
    private void assertMatch(PathMappings<String> pathmap, String path, String expectedValue)
    {
        String msg = String.format(".getMatched(\"%s\")", path);
        MatchedResource<String> matched = pathmap.getMatched(path);
        assertThat(msg, matched, notNullValue());
        String actualMatch = matched.getResource();
        assertEquals(expectedValue, actualMatch, msg);
    }

    /**
     * Test the match order rules with a mixed Servlet and regex path specs
     *
     * <ul>
     * <li>Exact match</li>
     * <li>Longest prefix match</li>
     * <li>Longest suffix match</li>
     * </ul>
     */
    @Test
    public void testMixedMatchOrder()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec(""), "root");
        p.put(new ServletPathSpec("/"), "default");
        p.put(new ServletPathSpec("/animal/bird/*"), "birds");
        p.put(new ServletPathSpec("/animal/fish/*"), "fishes");
        p.put(new ServletPathSpec("/animal/*"), "animals");
        p.put(new RegexPathSpec("^/animal/.*/chat$"), "animalChat");
        p.put(new RegexPathSpec("^/animal/.*/cam$"), "animalCam");
        p.put(new RegexPathSpec("^/entrance/cam$"), "entranceCam");

        assertMatch(p, "/animal/bird/eagle", "birds");
        assertMatch(p, "/animal/fish/bass/sea", "fishes");
        assertMatch(p, "/animal/peccary/javalina/evolution", "animals");
        assertMatch(p, "/", "root");
        assertMatch(p, "/other", "default");
        assertMatch(p, "/animal/bird/eagle/chat", "animalChat");
        assertMatch(p, "/animal/bird/penguin/chat", "animalChat");
        assertMatch(p, "/animal/fish/trout/cam", "animalCam");
        assertMatch(p, "/entrance/cam", "entranceCam");
    }

    /**
     * Test the match order rules imposed by the Servlet API (default vs any)
     */
    @Test
    public void testServletMatchDefault()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/"), "default");
        p.put(new ServletPathSpec("/*"), "any");

        assertMatch(p, "/abs/path", "any");
        assertMatch(p, "/abs/path/xxx", "any");
        assertMatch(p, "/animal/bird/eagle/bald", "any");
        assertMatch(p, "/", "any");
    }

    /**
     * Test the match order rules imposed by the Servlet API (any vs specific sub-dir)
     */
    @Test
    public void testServletMatchPrefix()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/*"), "any");
        p.put(new ServletPathSpec("/foo/*"), "foo");
        p.put(new ServletPathSpec("/food/*"), "food");
        p.put(new ServletPathSpec("/a/*"), "a");
        p.put(new ServletPathSpec("/a/b/*"), "ab");

        assertMatch(p, "/abs/path", "any");
        assertMatch(p, "/abs/foo/bar", "any");
        assertMatch(p, "/foo/bar", "foo");
        assertMatch(p, "/", "any");
        assertMatch(p, "/foo", "foo");
        assertMatch(p, "/fo", "any");
        assertMatch(p, "/foobar", "any");
        assertMatch(p, "/foob", "any");
        assertMatch(p, "/food", "food");
        assertMatch(p, "/food/zed", "food");
        assertMatch(p, "/foodie", "any");
        assertMatch(p, "/a/bc", "a");
        assertMatch(p, "/a/b/c", "ab");
        assertMatch(p, "/a/", "a");
        assertMatch(p, "/a", "a");

        // Try now with order important
        p.put(new RegexPathSpec("/other.*/"), "other");
        assertMatch(p, "/abs/path", "any");
        assertMatch(p, "/abs/foo/bar", "any");
        assertMatch(p, "/foo/bar", "foo");
        assertMatch(p, "/", "any");
        assertMatch(p, "/foo", "foo");
        assertMatch(p, "/fo", "any");
        assertMatch(p, "/foobar", "any");
        assertMatch(p, "/foob", "any");
        assertMatch(p, "/food", "food");
        assertMatch(p, "/food/zed", "food");
        assertMatch(p, "/foodie", "any");
        assertMatch(p, "/a/bc", "a");
        assertMatch(p, "/a/b/c", "ab");
        assertMatch(p, "/a/", "a");
        assertMatch(p, "/a", "a");
    }

    /**
     * Test the match order rules with a mixed Servlet and URI Template path specs
     *
     * <ul>
     * <li>Exact match</li>
     * <li>Longest prefix match</li>
     * <li>Longest suffix match</li>
     * </ul>
     */
    @Test
    public void testMixedMatchUriOrder()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/"), "default");
        p.put(new ServletPathSpec("/animal/bird/*"), "birds");
        p.put(new ServletPathSpec("/animal/fish/*"), "fishes");
        p.put(new ServletPathSpec("/animal/*"), "animals");
        p.put(new UriTemplatePathSpec("/animal/{type}/{name}/chat"), "animalChat");
        p.put(new UriTemplatePathSpec("/animal/{type}/{name}/cam"), "animalCam");
        p.put(new UriTemplatePathSpec("/entrance/cam"), "entranceCam");

        assertMatch(p, "/animal/bird/eagle", "birds");
        assertMatch(p, "/animal/fish/bass/sea", "fishes");
        assertMatch(p, "/animal/peccary/javalina/evolution", "animals");
        assertMatch(p, "/", "default");
        assertMatch(p, "/animal/bird/eagle/chat", "animalChat");
        assertMatch(p, "/animal/bird/penguin/chat", "animalChat");
        assertMatch(p, "/animal/fish/trout/cam", "animalCam");
        assertMatch(p, "/entrance/cam", "entranceCam");
    }

    /**
     * Test the match order rules for URI Template based specs
     *
     * <ul>
     * <li>Exact match</li>
     * <li>Longest prefix match</li>
     * <li>Longest suffix match</li>
     * </ul>
     */
    @Test
    public void testUriTemplateMatchOrder()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new UriTemplatePathSpec("/a/{var}/c"), "endpointA");
        p.put(new UriTemplatePathSpec("/a/b/c"), "endpointB");
        p.put(new UriTemplatePathSpec("/a/{var1}/{var2}"), "endpointC");
        p.put(new UriTemplatePathSpec("/{var1}/d"), "endpointD");
        p.put(new UriTemplatePathSpec("/b/{var2}"), "endpointE");

        assertMatch(p, "/a/b/c", "endpointB");
        assertMatch(p, "/a/d/c", "endpointA");
        assertMatch(p, "/a/x/y", "endpointC");

        assertMatch(p, "/b/d", "endpointE");
    }

    /**
     * Test the match order rules for mixed Servlet and Regex path specs
     */
    @Test
    public void testServletAndRegexMatchOrder()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/a/*"), "endpointA");
        p.put(new RegexPathSpec("^.*/middle/.*$"), "middle");
        p.put(new ServletPathSpec("*.do"), "endpointDo");
        p.put(new ServletPathSpec("/"), "default");

        assertMatch(p, "/a/b/c", "endpointA");
        assertMatch(p, "/a/middle/c", "endpointA");
        assertMatch(p, "/b/middle/c", "middle");
        assertMatch(p, "/x/y.do", "endpointDo");
        assertMatch(p, "/b/d", "default");
    }

    @Test
    public void testPathMap()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/abs/path"), "1");
        p.put(new ServletPathSpec("/abs/path/longer"), "2");
        p.put(new ServletPathSpec("/animal/bird/*"), "3");
        p.put(new ServletPathSpec("/animal/fish/*"), "4");
        p.put(new ServletPathSpec("/animal/*"), "5");
        p.put(new ServletPathSpec("*.tar.gz"), "6");
        p.put(new ServletPathSpec("*.gz"), "7");
        p.put(new ServletPathSpec("/"), "8");
        // p.put(new ServletPathSpec("/XXX:/YYY"), "9"); // special syntax from Jetty 3.1.x
        p.put(new ServletPathSpec(""), "10");
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        p.put(new ServletPathSpec("/\u20ACuro/*"), "11");
        // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck
        p.put(new ServletPathSpec("/prefix"), "12");
        p.put(new ServletPathSpec("/prefix/"), "13");
        p.put(new ServletPathSpec("/prefix/*"), "14");

        p.put(new ServletPathSpec("/*"), "0");

        assertEquals("/abs/path", p.getMatched("/abs/path").getPathSpec().getDeclaration(), "Match absolute path");
        assertEquals("1", p.getMatched("/abs/path").getResource(), "Match absolute path");
        assertEquals("0", p.getMatched("/abs/path/xxx").getResource(), "Mismatch absolute path");
        assertEquals("0", p.getMatched("/abs/pith").getResource(), "Mismatch absolute path");
        assertEquals("2", p.getMatched("/abs/path/longer").getResource(), "Match longer absolute path");
        assertEquals("0", p.getMatched("/abs/path/").getResource(), "Not exact absolute path");
        assertEquals("0", p.getMatched("/abs/path/xxx").getResource(), "Not exact absolute path");

        assertEquals("3", p.getMatched("/animal/bird/eagle/bald").getResource(), "Match longest prefix");
        assertEquals("4", p.getMatched("/animal/fish/shark/grey").getResource(), "Match longest prefix");
        assertEquals("5", p.getMatched("/animal/insect/bug").getResource(), "Match longest prefix");
        assertEquals("5", p.getMatched("/animal").getResource(), "mismatch exact prefix");
        assertEquals("5", p.getMatched("/animal/").getResource(), "mismatch exact prefix");

        assertEquals("0", p.getMatched("/suffix/path.tar.gz").getResource(), "Match longest suffix");
        assertEquals("0", p.getMatched("/suffix/path.gz").getResource(), "Match longest suffix");
        assertEquals("5", p.getMatched("/animal/path.gz").getResource(), "prefix rather than suffix");

        assertEquals("12", p.getMatched("/prefix").getResource());
        assertEquals("13", p.getMatched("/prefix/").getResource());
        assertEquals("14", p.getMatched("/prefix/info").getResource());

        assertEquals("0", p.getMatched("/Other/path").getResource(), "default");

        assertEquals("10", p.getMatched("/").getResource(), "match / with ''");
    }

    /**
     * See JIRA issue: JETTY-88.
     */
    @Test
    public void testPathMappingsOnlyMatchOnDirectoryNames()
    {
        ServletPathSpec spec = new ServletPathSpec("/xyz/*");

        PathSpecAssert.assertMatch(spec, "/xyz");
        PathSpecAssert.assertMatch(spec, "/xyz/");
        PathSpecAssert.assertMatch(spec, "/xyz/123");
        PathSpecAssert.assertMatch(spec, "/xyz/123/");
        PathSpecAssert.assertMatch(spec, "/xyz/123.txt");
        PathSpecAssert.assertNotMatch(spec, "/xyz123");
        PathSpecAssert.assertNotMatch(spec, "/xyz123;jessionid=99");
        PathSpecAssert.assertNotMatch(spec, "/xyz123/");
        PathSpecAssert.assertNotMatch(spec, "/xyz123/456");
        PathSpecAssert.assertNotMatch(spec, "/xyz.123");
        PathSpecAssert.assertNotMatch(spec, "/xyz;123"); // as if the ; was encoded and part of the path
        PathSpecAssert.assertNotMatch(spec, "/xyz?123"); // as if the ? was encoded and part of the path
    }

    @Test
    public void testPrecedenceVsOrdering()
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new ServletPathSpec("/dump/gzip/*"), "prefix");
        p.put(new ServletPathSpec("*.txt"), "suffix");

        assertNull(p.getMatched("/foo/bar"));
        assertEquals("prefix", p.getMatched("/dump/gzip/something").getResource());
        assertEquals("suffix", p.getMatched("/foo/something.txt").getResource());
        assertEquals("prefix", p.getMatched("/dump/gzip/something.txt").getResource());

        p = new PathMappings<>();
        p.put(new ServletPathSpec("*.txt"), "suffix");
        p.put(new ServletPathSpec("/dump/gzip/*"), "prefix");

        assertNull(p.getMatched("/foo/bar"));
        assertEquals("prefix", p.getMatched("/dump/gzip/something").getResource());
        assertEquals("suffix", p.getMatched("/foo/something.txt").getResource());
        assertEquals("prefix", p.getMatched("/dump/gzip/something.txt").getResource());
    }

    @Test
    public void testPutRejectsDuplicates()
    {
        PathMappings<String> p = new PathMappings<>();
        assertThat(p.put(new UriTemplatePathSpec("/a/{var2}/c"), "resourceA"), nullValue());
        assertThat(p.put(new UriTemplatePathSpec("/a/{var1}/c"), "resourceAA"), is("resourceA"));
        assertThat(p.put(new UriTemplatePathSpec("/a/b/c"), "resourceB"), nullValue());
        assertThat(p.put(new UriTemplatePathSpec("/a/b/c"), "resourceBB"), is("resourceB"));
        assertThat(p.put(new ServletPathSpec("/a/b/c"), "resourceBB"), nullValue());
        assertThat(p.put(new RegexPathSpec("/a/b/c"), "resourceBB"), nullValue());

        assertThat(p.put(new ServletPathSpec("/*"), "resourceC"), nullValue());
        assertThat(p.put(new RegexPathSpec("/(.*)"), "resourceCC"), nullValue());
    }

    @Test
    public void testGetUriTemplatePathSpec()
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new UriTemplatePathSpec("/a/{var1}/c"), "resourceA");
        p.put(new UriTemplatePathSpec("/a/b/c"), "resourceB");

        assertThat(p.get(new UriTemplatePathSpec("/a/{var1}/c")), equalTo("resourceA"));
        assertThat(p.get(new UriTemplatePathSpec("/a/{foo}/c")), equalTo("resourceA"));
        assertThat(p.get(new UriTemplatePathSpec("/a/b/c")), equalTo("resourceB"));
        assertThat(p.get(new UriTemplatePathSpec("/a/d/c")), nullValue());
        assertThat(p.get(new RegexPathSpec("/a/b/c")), nullValue());
    }

    @Test
    public void testGetRegexPathSpec()
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new RegexPathSpec("/a/b/c"), "resourceA");
        p.put(new RegexPathSpec("/(.*)/b/c"), "resourceB");
        p.put(new RegexPathSpec("/a/(.*)/c"), "resourceC");
        p.put(new RegexPathSpec("/a/b/(.*)"), "resourceD");

        assertThat(p.get(new RegexPathSpec("/a/(.*)/c")), equalTo("resourceC"));
        assertThat(p.get(new RegexPathSpec("/a/b/c")), equalTo("resourceA"));
        assertThat(p.get(new RegexPathSpec("/(.*)/b/c")), equalTo("resourceB"));
        assertThat(p.get(new RegexPathSpec("/a/b/(.*)")), equalTo("resourceD"));
        assertThat(p.get(new RegexPathSpec("/a/d/c")), nullValue());
        assertThat(p.get(new ServletPathSpec("/a/b/c")), nullValue());
    }

    @Test
    public void testGetServletPathSpec()
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new ServletPathSpec("/"), "resourceA");
        p.put(new ServletPathSpec("/*"), "resourceB");
        p.put(new ServletPathSpec("/a/*"), "resourceC");
        p.put(new ServletPathSpec("*.do"), "resourceD");

        assertThat(p.get(new ServletPathSpec("/")), equalTo("resourceA"));
        assertThat(p.get(new ServletPathSpec("/*")), equalTo("resourceB"));
        assertThat(p.get(new ServletPathSpec("/a/*")), equalTo("resourceC"));
        assertThat(p.get(new ServletPathSpec("*.do")), equalTo("resourceD"));
        assertThat(p.get(new ServletPathSpec("*.gz")), nullValue());
        assertThat(p.get(new ServletPathSpec("/a/b/*")), nullValue());
        assertThat(p.get(new ServletPathSpec("/a/d/c")), nullValue());
        assertThat(p.get(new RegexPathSpec("/a/b/c")), nullValue());
    }

    public static Stream<Arguments> suffixMatches()
    {
        return Stream.of(
            Arguments.of("/a.b.c.foo", "resourceFoo"),
            Arguments.of("/a.b.c.bar", "resourceBar"),
            Arguments.of("/a.b.c.foo.bar", "resourceFooBar"),
            Arguments.of("/a.b/c.d.foo", "resourceFoo"),
            Arguments.of("/a.b/c.d.bar", "resourceBar"),
            Arguments.of("/a.b/c.d.foo.bar", "resourceFooBar"),
            Arguments.of("/a.b.c.pop", null),
            Arguments.of("/a.foo.c.pop", null),
            Arguments.of("/a.foop", null),
            Arguments.of("/a%2Efoo", null)
            );
    }

    @ParameterizedTest
    @MethodSource("suffixMatches")
    public void testServletMultipleSuffixMappings(String path, String matched)
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new ServletPathSpec("*.foo"), "resourceFoo");
        p.put(new ServletPathSpec("*.bar"), "resourceBar");
        p.put(new ServletPathSpec("*.foo.bar"), "resourceFooBar");
        p.put(new ServletPathSpec("*.zed"), "resourceZed");

        MatchedResource<String> match = p.getMatched(path);
        if (matched == null)
            assertThat(match, nullValue());
        else
        {
            assertThat(match, notNullValue());
            assertThat(p.getMatched(path).getResource(), equalTo(matched));
        }
    }

    @Test
    public void testRemoveUriTemplatePathSpec()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new UriTemplatePathSpec("/a/{var1}/c"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/{var1}/c")), is("resourceA"));

        p.put(new UriTemplatePathSpec("/a/{var1}/c"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new UriTemplatePathSpec("/a/{b}/c")), is("resourceA"));
        assertThat(p.remove(new UriTemplatePathSpec("/a/{b}/c")), nullValue());

        p.put(new UriTemplatePathSpec("/{var1}/b/c"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/b/c")), is("resourceA"));
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/b/c")), nullValue());

        p.put(new UriTemplatePathSpec("/a/b/{var1}"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/{c}")), is("resourceA"));
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/{c}")), nullValue());

        p.put(new UriTemplatePathSpec("/{var1}/{var2}/{var3}"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/{b}/{c}")), is("resourceA"));
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/{b}/{c}")), nullValue());
    }

    @Test
    public void testRemoveRegexPathSpec()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new RegexPathSpec("/a/(.*)/c"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new RegexPathSpec("/a/(.*)/c")), is("resourceA"));
        assertThat(p.remove(new RegexPathSpec("/a/(.*)/c")), nullValue());

        p.put(new RegexPathSpec("/(.*)/b/c"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new RegexPathSpec("/(.*)/b/c")), is("resourceA"));
        assertThat(p.remove(new RegexPathSpec("/(.*)/b/c")), nullValue());

        p.put(new RegexPathSpec("/a/b/(.*)"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new RegexPathSpec("/a/b/(.*)")), is("resourceA"));
        assertThat(p.remove(new RegexPathSpec("/a/b/(.*)")), nullValue());

        p.put(new RegexPathSpec("/a/b/c"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/d")), nullValue());
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), is("resourceA"));
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), nullValue());
    }

    @Test
    public void testRemoveServletPathSpec()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/a/*"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a/b")), nullValue());
        assertThat(p.remove(new ServletPathSpec("/a/*")), is("resourceA"));
        assertThat(p.remove(new ServletPathSpec("/a/*")), nullValue());

        p.put(new ServletPathSpec("/a/b/*"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a/b/c")), nullValue());
        assertThat(p.remove(new ServletPathSpec("/a/b/*")), is("resourceA"));
        assertThat(p.remove(new ServletPathSpec("/a/b/*")), nullValue());

        p.put(new ServletPathSpec("*.do"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("*.gz")), nullValue());
        assertThat(p.remove(new ServletPathSpec("*.do")), is("resourceA"));
        assertThat(p.remove(new ServletPathSpec("*.do")), nullValue());

        p.put(new ServletPathSpec("/"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a")), nullValue());
        assertThat(p.remove(new ServletPathSpec("/")), is("resourceA"));
        assertThat(p.remove(new ServletPathSpec("/")), nullValue());

        p.put(new ServletPathSpec(""), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/")), nullValue());
        assertThat(p.remove(new ServletPathSpec("")), is("resourceA"));
        assertThat(p.remove(new ServletPathSpec("")), nullValue());

        p.put(new ServletPathSpec("/a/b/c"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a/b/d")), nullValue());
        assertThat(p.remove(new ServletPathSpec("/a/b/c")), is("resourceA"));
        assertThat(p.remove(new ServletPathSpec("/a/b/c")), nullValue());
    }

    @Test
    public void testAsPathSpec()
    {
        assertThat(PathSpec.from(""), instanceOf(ServletPathSpec.class));
        assertThat(PathSpec.from("/"), instanceOf(ServletPathSpec.class));
        assertThat(PathSpec.from("/*"), instanceOf(ServletPathSpec.class));
        assertThat(PathSpec.from("/foo/*"), instanceOf(ServletPathSpec.class));
        assertThat(PathSpec.from("*.jsp"), instanceOf(ServletPathSpec.class));

        assertThat(PathSpec.from("^$"), instanceOf(RegexPathSpec.class));
        assertThat(PathSpec.from("^.*"), instanceOf(RegexPathSpec.class));
        assertThat(PathSpec.from("^/"), instanceOf(RegexPathSpec.class));
    }

    @Test
    public void testOptimalExactIterative()
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new ServletPathSpec(""), "resourceR");
        p.put(new ServletPathSpec("/"), "resourceD");
        p.put(new ServletPathSpec("/exact"), "resourceE");
        p.put(new ServletPathSpec("/a/*"), "resourceP");
        p.put(new ServletPathSpec("*.do"), "resourceS");

        // Add an non-servlet Exact to avoid non iterative
        RegexPathSpec regexPathSpec = new RegexPathSpec("^/some/exact$");
        p.put(regexPathSpec, "someExact");

        assertThat(p.getMatched("/").getResource(), equalTo("resourceR"));
        assertThat(p.getMatched("/something").getResource(), equalTo("resourceD"));
        assertThat(p.getMatched("/exact").getResource(), equalTo("resourceE"));
        assertThat(p.getMatched("/a").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/a/").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/a/info").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/foo.do").getResource(), equalTo("resourceS"));
        assertThat(p.getMatched("/a/foo.do").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/b/foo.do").getResource(), equalTo("resourceS"));

        // Make regex a prefix match to test iterative exact match code
        p.remove(regexPathSpec);
        regexPathSpec = new RegexPathSpec("/prefix/.*");
        p.put(regexPathSpec, "somePrefix");

        assertThat(p.getMatched("/").getResource(), equalTo("resourceR"));
        assertThat(p.getMatched("/something").getResource(), equalTo("resourceD"));
        assertThat(p.getMatched("/exact").getResource(), equalTo("resourceE"));
        assertThat(p.getMatched("/a").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/a/").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/a/info").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/foo.do").getResource(), equalTo("resourceS"));
        assertThat(p.getMatched("/a/foo.do").getResource(), equalTo("resourceP"));
        assertThat(p.getMatched("/b/foo.do").getResource(), equalTo("resourceS"));
    }

    @Test
    public void testAsMap()
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new ServletPathSpec(""), "resourceR");
        p.put(new ServletPathSpec("/"), "resourceD");
        p.put(new ServletPathSpec("/exact"), "REPLACED");
        p.put(new ServletPathSpec("/exact"), "resourceE");
        p.put(new ServletPathSpec("/a/*"), "resourceP");
        p.put(new ServletPathSpec("*.do"), "resourceS");

        @SuppressWarnings("redundant")
        Map<PathSpec, String> map = p;

        assertThat(map.keySet(), containsInAnyOrder(
            new ServletPathSpec(""),
            new ServletPathSpec("/"),
            new ServletPathSpec("/exact"),
            new ServletPathSpec("/a/*"),
            new ServletPathSpec("*.do")
            ));

        assertThat(map.values(), containsInAnyOrder(
            "resourceR",
            "resourceD",
            "resourceE",
            "resourceP",
            "resourceS"
            ));
    }

}
