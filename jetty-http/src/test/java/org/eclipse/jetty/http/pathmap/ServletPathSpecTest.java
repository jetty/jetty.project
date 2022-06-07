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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServletPathSpecTest
{
    private void assertMatches(ServletPathSpec spec, String path)
    {
        String msg = String.format("Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertThat(msg, spec.matched(path), not(nullValue()));
    }

    private void assertNotMatches(ServletPathSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertThat(msg, spec.matched(path), is(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "foo",
        "/foo/*.do",
        "foo/*.do",
        "foo/*.*do",
        "*",
        "*do",
        "/foo/*/bar",
        "*/foo",
        "*.foo/*"
    })
    public void testBadPathSpecs(String str)
    {
        assertThrows(IllegalArgumentException.class, () -> new ServletPathSpec(str));
    }

    @Test
    public void testDefaultPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("/");
        assertEquals("/", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals(-1, spec.getPathDepth(), "Spec.pathDepth");
    }

    @Test
    public void testExactPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("/abs/path");
        assertEquals("/abs/path", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");

        assertMatches(spec, "/abs/path");

        assertNotMatches(spec, "/abs/path/");
        assertNotMatches(spec, "/abs/path/more");
        assertNotMatches(spec, "/foo");
        assertNotMatches(spec, "/foo/abs/path");
        assertNotMatches(spec, "/foo/abs/path/");
    }

    @Test
    public void testGetPathInfo()
    {
        ServletPathSpec spec = new ServletPathSpec("/Foo/bar");
        assertThat("PathInfo exact", spec.matched("/Foo/bar").getPathInfo(), is(nullValue()));

        spec = new ServletPathSpec("/Foo/*");
        assertThat("PathInfo prefix", spec.matched("/Foo/bar").getPathInfo(), is("/bar"));
        assertThat("PathInfo prefix", spec.matched("/Foo/*").getPathInfo(), is("/*"));
        assertThat("PathInfo prefix", spec.matched("/Foo/").getPathInfo(), is("/"));
        assertThat("PathInfo prefix", spec.matched("/Foo").getPathInfo(), is(nullValue()));

        spec = new ServletPathSpec("*.ext");
        assertThat("PathInfo suffix", spec.matched("/Foo/bar.ext").getPathInfo(), is(nullValue()));

        spec = new ServletPathSpec("/");
        assertThat("PathInfo default", spec.matched("/Foo/bar.ext").getPathInfo(), is(nullValue()));

        spec = new ServletPathSpec("");
        assertThat("PathInfo root", spec.matched("/").getPathInfo(), is("/"));
        assertThat("PathInfo root", spec.matched(""), is(nullValue())); // does not match // TODO: verify with greg

        spec = new ServletPathSpec("/*");
        assertThat("PathInfo default", spec.matched("/xxx/zzz").getPathInfo(), is("/xxx/zzz"));
    }

    @Test
    public void testNullPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec(null);
        assertEquals("", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals(-1, spec.getPathDepth(), "Spec.pathDepth");
    }

    @Test
    public void testRootPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("");
        assertEquals("", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals(-1, spec.getPathDepth(), "Spec.pathDepth");
    }

    @Test
    public void testPathMatch()
    {
        ServletPathSpec spec = new ServletPathSpec("/Foo/bar");
        assertThat("PathMatch exact", spec.matched("/Foo/bar").getPathMatch(), is("/Foo/bar"));

        spec = new ServletPathSpec("/Foo/*");
        assertThat("PathMatch prefix", spec.matched("/Foo/bar").getPathMatch(), is("/Foo"));
        assertThat("PathMatch prefix", spec.matched("/Foo/").getPathMatch(), is("/Foo"));
        assertThat("PathMatch prefix", spec.matched("/Foo").getPathMatch(), is("/Foo"));

        spec = new ServletPathSpec("*.ext");
        assertThat("PathMatch suffix", spec.matched("/Foo/bar.ext").getPathMatch(), is("/Foo/bar.ext"));

        spec = new ServletPathSpec("/");
        assertThat("PathMatch default", spec.matched("/Foo/bar.ext").getPathMatch(), is("/Foo/bar.ext"));

        spec = new ServletPathSpec("");
        assertThat("PathMatch root", spec.matched("/").getPathMatch(), is(""));
        assertThat("PathMatch root", spec.matched(""), is(nullValue())); // does not match // TODO: verify with greg

        spec = new ServletPathSpec("/*");
        assertThat("PathMatch default", spec.matched("/xxx/zzz").getPathMatch(), is(""));
    }

    @Test
    public void testPrefixPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("/downloads/*");
        assertEquals("/downloads/*", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");

        assertMatches(spec, "/downloads/logo.jpg");
        assertMatches(spec, "/downloads/distribution.tar.gz");
        assertMatches(spec, "/downloads/distribution.tgz");
        assertMatches(spec, "/downloads/distribution.zip");

        assertMatches(spec, "/downloads");

        MatchedPath matched = spec.matched("/downloads/");
        assertThat("matched.pathMatch", matched.getPathMatch(), is("/downloads"));
        assertThat("matched.pathInfo", matched.getPathInfo(), is("/"));

        matched = spec.matched("/downloads/distribution.zip");
        assertThat("matched.pathMatch", matched.getPathMatch(), is("/downloads"));
        assertThat("matched.pathInfo", matched.getPathInfo(), is("/distribution.zip"));

        matched = spec.matched("/downloads/dist/9.0/distribution.tar.gz");
        assertThat("matched.pathMatch", matched.getPathMatch(), is("/downloads"));
        assertThat("matched.pathInfo", matched.getPathInfo(), is("/dist/9.0/distribution.tar.gz"));
    }

    @Test
    public void testMatches()
    {
        assertTrue(new ServletPathSpec("/").matches("/anything"), "match /");
        assertTrue(new ServletPathSpec("/*").matches("/anything"), "match /*");
        assertTrue(new ServletPathSpec("/foo").matches("/foo"), "match /foo");
        assertFalse(new ServletPathSpec("/foo").matches("/bar"), "!match /foo");
        assertTrue(new ServletPathSpec("/foo/*").matches("/foo"), "match /foo/*");
        assertTrue(new ServletPathSpec("/foo/*").matches("/foo/"), "match /foo/*");
        assertTrue(new ServletPathSpec("/foo/*").matches("/foo/anything"), "match /foo/*");
        assertFalse(new ServletPathSpec("/foo/*").matches("/bar"), "!match /foo/*");
        assertFalse(new ServletPathSpec("/foo/*").matches("/bar/"), "!match /foo/*");
        assertFalse(new ServletPathSpec("/foo/*").matches("/bar/anything"), "!match /foo/*");
        assertTrue(new ServletPathSpec("*.foo").matches("anything.foo"), "match *.foo");
        assertFalse(new ServletPathSpec("*.foo").matches("anything.bar"), "!match *.foo");
        assertTrue(new ServletPathSpec("/On*").matches("/On*"), "match /On*");
        assertFalse(new ServletPathSpec("/On*").matches("/One"), "!match /One");

        assertTrue(new ServletPathSpec("").matches("/"), "match \"\"");

    }

    @Test
    public void testSuffixPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("*.gz");
        assertEquals("*.gz", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals(0, spec.getPathDepth(), "Spec.pathDepth");

        assertMatches(spec, "/downloads/distribution.tar.gz");
        assertMatches(spec, "/downloads/jetty.log.gz");

        assertNotMatches(spec, "/downloads/distribution.zip");
        assertNotMatches(spec, "/downloads/distribution.tgz");
        assertNotMatches(spec, "/abs/path");

        MatchedPath matched = spec.matched("/downloads/distribution.tar.gz");
        assertThat("Suffix.pathMatch", matched.getPathMatch(), is("/downloads/distribution.tar.gz"));
        assertThat("Suffix.pathInfo", matched.getPathInfo(), is(nullValue()));
    }

    @Test
    public void testEquals()
    {
        assertThat(new ServletPathSpec("*.gz"), equalTo(new ServletPathSpec("*.gz")));
        assertThat(new ServletPathSpec("/foo"), equalTo(new ServletPathSpec("/foo")));
        assertThat(new ServletPathSpec("/foo/bar"), equalTo(new ServletPathSpec("/foo/bar")));
        assertThat(new ServletPathSpec("*.gz"), not(equalTo(new ServletPathSpec("*.do"))));
        assertThat(new ServletPathSpec("/foo"), not(equalTo(new ServletPathSpec("/bar"))));
        assertThat(new ServletPathSpec("/bar/foo"), not(equalTo(new ServletPathSpec("/foo/bar"))));
        assertThat(new ServletPathSpec("/foo"), not(equalTo(new RegexPathSpec("/foo"))));
    }
}
