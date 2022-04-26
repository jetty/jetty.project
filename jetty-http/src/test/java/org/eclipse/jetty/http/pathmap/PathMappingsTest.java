//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.pathmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class PathMappingsTest
{
    private void assertMatch(PathMappings<String> pathmap, String path, String expectedValue)
    {
        String msg = String.format(".getMatch(\"%s\")", path);
        MappedResource<String> match = pathmap.getMatch(path);
        assertThat(msg, match, notNullValue());
        String actualMatch = match.getResource();
        assertEquals(expectedValue, actualMatch, msg);
    }

    public void dumpMappings(PathMappings<String> p)
    {
        for (MappedResource<String> res : p)
        {
            System.out.printf("  %s%n", res);
        }
    }

    /**
     * Test the match order rules with a mixed Servlet and regex path specs
     * <p>
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

        // dumpMappings(p);

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
     * Test the match order rules with a mixed Servlet and URI Template path specs
     * <p>
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

        // dumpMappings(p);

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
     * <p>
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

        // dumpMappings(p);

        assertMatch(p, "/a/b/c", "endpointB");
        assertMatch(p, "/a/d/c", "endpointA");
        assertMatch(p, "/a/x/y", "endpointC");

        assertMatch(p, "/b/d", "endpointE");
    }

    @Test
    public void testPathMap() throws Exception
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
        p.put(new ServletPathSpec("/\u20ACuro/*"), "11");

        assertEquals("/Foo/bar", new ServletPathSpec("/Foo/bar").getPathMatch("/Foo/bar"), "pathMatch exact");
        assertEquals("/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo/bar"), "pathMatch prefix");
        assertEquals("/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo/"), "pathMatch prefix");
        assertEquals("/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo"), "pathMatch prefix");
        assertEquals("/Foo/bar.ext", new ServletPathSpec("*.ext").getPathMatch("/Foo/bar.ext"), "pathMatch suffix");
        assertEquals("/Foo/bar.ext", new ServletPathSpec("/").getPathMatch("/Foo/bar.ext"), "pathMatch default");

        assertEquals(null, new ServletPathSpec("/Foo/bar").getPathInfo("/Foo/bar"), "pathInfo exact");
        assertEquals("/bar", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/bar"), "pathInfo prefix");
        assertEquals("/*", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/*"), "pathInfo prefix");
        assertEquals("/", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/"), "pathInfo prefix");
        assertEquals(null, new ServletPathSpec("/Foo/*").getPathInfo("/Foo"), "pathInfo prefix");
        assertEquals(null, new ServletPathSpec("*.ext").getPathInfo("/Foo/bar.ext"), "pathInfo suffix");
        assertEquals(null, new ServletPathSpec("/").getPathInfo("/Foo/bar.ext"), "pathInfo default");

        p.put(new ServletPathSpec("/*"), "0");

        // assertEquals("1", p.get("/abs/path"), "Get absolute path");
        assertEquals("/abs/path", p.getMatch("/abs/path").getPathSpec().getDeclaration(), "Match absolute path");
        assertEquals("1", p.getMatch("/abs/path").getResource(), "Match absolute path");
        assertEquals("0", p.getMatch("/abs/path/xxx").getResource(), "Mismatch absolute path");
        assertEquals("0", p.getMatch("/abs/pith").getResource(), "Mismatch absolute path");
        assertEquals("2", p.getMatch("/abs/path/longer").getResource(), "Match longer absolute path");
        assertEquals("0", p.getMatch("/abs/path/").getResource(), "Not exact absolute path");
        assertEquals("0", p.getMatch("/abs/path/xxx").getResource(), "Not exact absolute path");

        assertEquals("3", p.getMatch("/animal/bird/eagle/bald").getResource(), "Match longest prefix");
        assertEquals("4", p.getMatch("/animal/fish/shark/grey").getResource(), "Match longest prefix");
        assertEquals("5", p.getMatch("/animal/insect/bug").getResource(), "Match longest prefix");
        assertEquals("5", p.getMatch("/animal").getResource(), "mismatch exact prefix");
        assertEquals("5", p.getMatch("/animal/").getResource(), "mismatch exact prefix");

        assertEquals("0", p.getMatch("/suffix/path.tar.gz").getResource(), "Match longest suffix");
        assertEquals("0", p.getMatch("/suffix/path.gz").getResource(), "Match longest suffix");
        assertEquals("5", p.getMatch("/animal/path.gz").getResource(), "prefix rather than suffix");

        assertEquals("0", p.getMatch("/Other/path").getResource(), "default");

        assertEquals("", new ServletPathSpec("/*").getPathMatch("/xxx/zzz"), "pathMatch /*");
        assertEquals("/xxx/zzz", new ServletPathSpec("/*").getPathInfo("/xxx/zzz"), "pathInfo /*");

        assertTrue(new ServletPathSpec("/").matches("/anything"), "match /");
        assertTrue(new ServletPathSpec("/*").matches("/anything"), "match /*");
        assertTrue(new ServletPathSpec("/foo").matches("/foo"), "match /foo");
        assertTrue(!new ServletPathSpec("/foo").matches("/bar"), "!match /foo");
        assertTrue(new ServletPathSpec("/foo/*").matches("/foo"), "match /foo/*");
        assertTrue(new ServletPathSpec("/foo/*").matches("/foo/"), "match /foo/*");
        assertTrue(new ServletPathSpec("/foo/*").matches("/foo/anything"), "match /foo/*");
        assertTrue(!new ServletPathSpec("/foo/*").matches("/bar"), "!match /foo/*");
        assertTrue(!new ServletPathSpec("/foo/*").matches("/bar/"), "!match /foo/*");
        assertTrue(!new ServletPathSpec("/foo/*").matches("/bar/anything"), "!match /foo/*");
        assertTrue(new ServletPathSpec("*.foo").matches("anything.foo"), "match *.foo");
        assertTrue(!new ServletPathSpec("*.foo").matches("anything.bar"), "!match *.foo");
        assertTrue(new ServletPathSpec("/On*").matches("/On*"), "match /On*");
        assertTrue(!new ServletPathSpec("/On*").matches("/One"), "!match /One");

        assertEquals("10", p.getMatch("/").getResource(), "match / with ''");

        assertTrue(new ServletPathSpec("").matches("/"), "match \"\"");
    }

    /**
     * See JIRA issue: JETTY-88.
     *
     * @throws Exception failed test
     */
    @Test
    public void testPathMappingsOnlyMatchOnDirectoryNames() throws Exception
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
    public void testPrecidenceVsOrdering() throws Exception
    {
        PathMappings<String> p = new PathMappings<>();
        p.put(new ServletPathSpec("/dump/gzip/*"), "prefix");
        p.put(new ServletPathSpec("*.txt"), "suffix");

        assertEquals(null, p.getMatch("/foo/bar"));
        assertEquals("prefix", p.getMatch("/dump/gzip/something").getResource());
        assertEquals("suffix", p.getMatch("/foo/something.txt").getResource());
        assertEquals("prefix", p.getMatch("/dump/gzip/something.txt").getResource());

        p = new PathMappings<>();
        p.put(new ServletPathSpec("*.txt"), "suffix");
        p.put(new ServletPathSpec("/dump/gzip/*"), "prefix");

        assertEquals(null, p.getMatch("/foo/bar"));
        assertEquals("prefix", p.getMatch("/dump/gzip/something").getResource());
        assertEquals("suffix", p.getMatch("/foo/something.txt").getResource());
        assertEquals("prefix", p.getMatch("/dump/gzip/something.txt").getResource());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "*",
        "/foo/*/bar",
        "*/foo",
        "*.foo/*"
    })
    public void testBadPathSpecs(String str)
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            new ServletPathSpec(str);
        });
    }

    @Test
    public void testPutRejectsDuplicates()
    {
        PathMappings<String> p = new PathMappings<>();
        assertThat(p.put(new UriTemplatePathSpec("/a/{var1}/c"), "resourceA"), is(true));
        assertThat(p.put(new UriTemplatePathSpec("/a/{var2}/c"), "resourceAA"), is(false));
        assertThat(p.put(new UriTemplatePathSpec("/a/b/c"), "resourceB"), is(true));
        assertThat(p.put(new UriTemplatePathSpec("/a/b/c"), "resourceBB"), is(false));
        assertThat(p.put(new ServletPathSpec("/a/b/c"), "resourceBB"), is(false));
        assertThat(p.put(new RegexPathSpec("/a/b/c"), "resourceBB"), is(false));

        assertThat(p.put(new ServletPathSpec("/*"), "resourceC"), is(true));
        assertThat(p.put(new RegexPathSpec("/(.*)"), "resourceCC"), is(true));
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

    @Test
    public void testRemoveUriTemplatePathSpec()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new UriTemplatePathSpec("/a/{var1}/c"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/{var1}/c")), is(true));

        p.put(new UriTemplatePathSpec("/a/{var1}/c"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new UriTemplatePathSpec("/a/{b}/c")), is(true));
        assertThat(p.remove(new UriTemplatePathSpec("/a/{b}/c")), is(false));

        p.put(new UriTemplatePathSpec("/{var1}/b/c"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/b/c")), is(true));
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/b/c")), is(false));

        p.put(new UriTemplatePathSpec("/a/b/{var1}"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/{c}")), is(true));
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/{c}")), is(false));

        p.put(new UriTemplatePathSpec("/{var1}/{var2}/{var3}"), "resourceA");
        assertThat(p.remove(new UriTemplatePathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/{b}/{c}")), is(true));
        assertThat(p.remove(new UriTemplatePathSpec("/{a}/{b}/{c}")), is(false));
    }

    @Test
    public void testRemoveRegexPathSpec()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new RegexPathSpec("/a/(.*)/c"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new RegexPathSpec("/a/(.*)/c")), is(true));
        assertThat(p.remove(new RegexPathSpec("/a/(.*)/c")), is(false));

        p.put(new RegexPathSpec("/(.*)/b/c"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new RegexPathSpec("/(.*)/b/c")), is(true));
        assertThat(p.remove(new RegexPathSpec("/(.*)/b/c")), is(false));

        p.put(new RegexPathSpec("/a/b/(.*)"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new RegexPathSpec("/a/b/(.*)")), is(true));
        assertThat(p.remove(new RegexPathSpec("/a/b/(.*)")), is(false));

        p.put(new RegexPathSpec("/a/b/c"), "resourceA");
        assertThat(p.remove(new RegexPathSpec("/a/b/d")), is(false));
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), is(true));
        assertThat(p.remove(new RegexPathSpec("/a/b/c")), is(false));
    }

    @Test
    public void testRemoveServletPathSpec()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/a/*"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a/b")), is(false));
        assertThat(p.remove(new ServletPathSpec("/a/*")), is(true));
        assertThat(p.remove(new ServletPathSpec("/a/*")), is(false));

        p.put(new ServletPathSpec("/a/b/*"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a/b/c")), is(false));
        assertThat(p.remove(new ServletPathSpec("/a/b/*")), is(true));
        assertThat(p.remove(new ServletPathSpec("/a/b/*")), is(false));

        p.put(new ServletPathSpec("*.do"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("*.gz")), is(false));
        assertThat(p.remove(new ServletPathSpec("*.do")), is(true));
        assertThat(p.remove(new ServletPathSpec("*.do")), is(false));

        p.put(new ServletPathSpec("/"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a")), is(false));
        assertThat(p.remove(new ServletPathSpec("/")), is(true));
        assertThat(p.remove(new ServletPathSpec("/")), is(false));

        p.put(new ServletPathSpec(""), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/")), is(false));
        assertThat(p.remove(new ServletPathSpec("")), is(true));
        assertThat(p.remove(new ServletPathSpec("")), is(false));

        p.put(new ServletPathSpec("/a/b/c"), "resourceA");
        assertThat(p.remove(new ServletPathSpec("/a/b/d")), is(false));
        assertThat(p.remove(new ServletPathSpec("/a/b/c")), is(true));
        assertThat(p.remove(new ServletPathSpec("/a/b/c")), is(false));
    }

    @Test
    public void testAsPathSpec()
    {
        assertThat(PathMappings.asPathSpec(""), instanceOf(ServletPathSpec.class));
        assertThat(PathMappings.asPathSpec("/"), instanceOf(ServletPathSpec.class));
        assertThat(PathMappings.asPathSpec("/*"), instanceOf(ServletPathSpec.class));
        assertThat(PathMappings.asPathSpec("/foo/*"), instanceOf(ServletPathSpec.class));
        assertThat(PathMappings.asPathSpec("*.jsp"), instanceOf(ServletPathSpec.class));

        assertThat(PathMappings.asPathSpec("^$"), instanceOf(RegexPathSpec.class));
        assertThat(PathMappings.asPathSpec("^.*"), instanceOf(RegexPathSpec.class));
        assertThat(PathMappings.asPathSpec("^/"), instanceOf(RegexPathSpec.class));
    }
}
