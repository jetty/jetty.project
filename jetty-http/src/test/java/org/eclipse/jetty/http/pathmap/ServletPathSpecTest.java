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

package org.eclipse.jetty.http.pathmap;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class ServletPathSpecTest
{
    private void assertBadServletPathSpec(String pathSpec)
    {
        try
        {
            new ServletPathSpec(pathSpec);
            fail("Expected IllegalArgumentException for a bad servlet pathspec on: " + pathSpec);
        }
        catch (IllegalArgumentException e)
        {
            // expected path
            System.out.println(e);
        }
    }

    private void assertMatches(ServletPathSpec spec, String path)
    {
        String msg = String.format("Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertThat(msg, spec.matches(path), is(true));
    }

    private void assertNotMatches(ServletPathSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertThat(msg, spec.matches(path), is(false));
    }

    @Test
    public void testBadServletPathSpecA()
    {
        assertBadServletPathSpec("foo");
    }

    @Test
    public void testBadServletPathSpecB()
    {
        assertBadServletPathSpec("/foo/*.do");
    }

    @Test
    public void testBadServletPathSpecC()
    {
        assertBadServletPathSpec("foo/*.do");
    }

    @Test
    public void testBadServletPathSpecD()
    {
        assertBadServletPathSpec("foo/*.*do");
    }

    @Test
    public void testBadServletPathSpecE()
    {
        assertBadServletPathSpec("*do");
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
        assertEquals(null, new ServletPathSpec("/Foo/bar").getPathInfo("/Foo/bar"), "pathInfo exact");
        assertEquals("/bar", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/bar"), "pathInfo prefix");
        assertEquals("/*", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/*"), "pathInfo prefix");
        assertEquals("/", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/"), "pathInfo prefix");
        assertEquals(null, new ServletPathSpec("/Foo/*").getPathInfo("/Foo"), "pathInfo prefix");
        assertEquals(null, new ServletPathSpec("*.ext").getPathInfo("/Foo/bar.ext"), "pathInfo suffix");
        assertEquals(null, new ServletPathSpec("/").getPathInfo("/Foo/bar.ext"), "pathInfo default");
        assertEquals("/", new ServletPathSpec("").getPathInfo("/"), "pathInfo root");
        assertEquals("", new ServletPathSpec("").getPathInfo(""), "pathInfo root");
        assertEquals("/xxx/zzz", new ServletPathSpec("/*").getPathInfo("/xxx/zzz"), "pathInfo default");
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
        assertEquals("/Foo/bar", new ServletPathSpec("/Foo/bar").getPathMatch("/Foo/bar"), "pathMatch exact");
        assertEquals("/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo/bar"), "pathMatch prefix");
        assertEquals("/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo/"), "pathMatch prefix");
        assertEquals("/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo"), "pathMatch prefix");
        assertEquals("/Foo/bar.ext", new ServletPathSpec("*.ext").getPathMatch("/Foo/bar.ext"), "pathMatch suffix");
        assertEquals("/Foo/bar.ext", new ServletPathSpec("/").getPathMatch("/Foo/bar.ext"), "pathMatch default");
        assertEquals("", new ServletPathSpec("").getPathMatch("/"), "pathInfo root");
        assertEquals("", new ServletPathSpec("").getPathMatch(""), "pathInfo root");
        assertEquals("", new ServletPathSpec("/*").getPathMatch("/xxx/zzz"), "pathMatch default");
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

        assertEquals("/", spec.getPathInfo("/downloads/"), "Spec.pathInfo");
        assertEquals("/distribution.zip", spec.getPathInfo("/downloads/distribution.zip"), "Spec.pathInfo");
        assertEquals("/dist/9.0/distribution.tar.gz", spec.getPathInfo("/downloads/dist/9.0/distribution.tar.gz"), "Spec.pathInfo");
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

        assertEquals(null, spec.getPathInfo("/downloads/distribution.tar.gz"), "Spec.pathInfo");
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
