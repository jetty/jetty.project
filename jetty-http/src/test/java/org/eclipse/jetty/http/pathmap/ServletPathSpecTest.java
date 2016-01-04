//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;

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
        String msg = String.format("Spec(\"%s\").matches(\"%s\")",spec.getDeclaration(),path);
        assertThat(msg,spec.matches(path),is(true));
    }

    private void assertNotMatches(ServletPathSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")",spec.getDeclaration(),path);
        assertThat(msg,spec.matches(path),is(false));
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
        assertEquals("Spec.pathSpec","/",spec.getDeclaration());
        assertEquals("Spec.pathDepth",-1,spec.getPathDepth());
    }

    @Test
    public void testExactPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("/abs/path");
        assertEquals("Spec.pathSpec","/abs/path",spec.getDeclaration());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());

        assertMatches(spec,"/abs/path");
        
        assertNotMatches(spec,"/abs/path/");
        assertNotMatches(spec,"/abs/path/more");
        assertNotMatches(spec,"/foo");
        assertNotMatches(spec,"/foo/abs/path");
        assertNotMatches(spec,"/foo/abs/path/");
    }

    @Test
    public void testGetPathInfo()
    {
        assertEquals("pathInfo exact",null,new ServletPathSpec("/Foo/bar").getPathInfo("/Foo/bar"));
        assertEquals("pathInfo prefix","/bar",new ServletPathSpec("/Foo/*").getPathInfo("/Foo/bar"));
        assertEquals("pathInfo prefix","/*",new ServletPathSpec("/Foo/*").getPathInfo("/Foo/*"));
        assertEquals("pathInfo prefix","/",new ServletPathSpec("/Foo/*").getPathInfo("/Foo/"));
        assertEquals("pathInfo prefix",null,new ServletPathSpec("/Foo/*").getPathInfo("/Foo"));
        assertEquals("pathInfo suffix",null,new ServletPathSpec("*.ext").getPathInfo("/Foo/bar.ext"));
        assertEquals("pathInfo default",null,new ServletPathSpec("/").getPathInfo("/Foo/bar.ext"));

        assertEquals("pathInfo default","/xxx/zzz",new ServletPathSpec("/*").getPathInfo("/xxx/zzz"));
    }

    @Test
    public void testNullPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec(null);
        assertEquals("Spec.pathSpec","",spec.getDeclaration());
        assertEquals("Spec.pathDepth",-1,spec.getPathDepth());
    }
    
    @Test
    public void testRootPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("");
        assertEquals("Spec.pathSpec","",spec.getDeclaration());
        assertEquals("Spec.pathDepth",-1,spec.getPathDepth());
    }

    @Test
    public void testPathMatch()
    {
        assertEquals("pathMatch exact","/Foo/bar",new ServletPathSpec("/Foo/bar").getPathMatch("/Foo/bar"));
        assertEquals("pathMatch prefix","/Foo",new ServletPathSpec("/Foo/*").getPathMatch("/Foo/bar"));
        assertEquals("pathMatch prefix","/Foo",new ServletPathSpec("/Foo/*").getPathMatch("/Foo/"));
        assertEquals("pathMatch prefix","/Foo",new ServletPathSpec("/Foo/*").getPathMatch("/Foo"));
        assertEquals("pathMatch suffix","/Foo/bar.ext",new ServletPathSpec("*.ext").getPathMatch("/Foo/bar.ext"));
        assertEquals("pathMatch default","/Foo/bar.ext",new ServletPathSpec("/").getPathMatch("/Foo/bar.ext"));

        assertEquals("pathMatch default","",new ServletPathSpec("/*").getPathMatch("/xxx/zzz"));
    }

    @Test
    public void testPrefixPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("/downloads/*");
        assertEquals("Spec.pathSpec","/downloads/*",spec.getDeclaration());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());

        assertMatches(spec,"/downloads/logo.jpg");
        assertMatches(spec,"/downloads/distribution.tar.gz");
        assertMatches(spec,"/downloads/distribution.tgz");
        assertMatches(spec,"/downloads/distribution.zip");

        assertMatches(spec,"/downloads");

        assertEquals("Spec.pathInfo","/",spec.getPathInfo("/downloads/"));
        assertEquals("Spec.pathInfo","/distribution.zip",spec.getPathInfo("/downloads/distribution.zip"));
        assertEquals("Spec.pathInfo","/dist/9.0/distribution.tar.gz",spec.getPathInfo("/downloads/dist/9.0/distribution.tar.gz"));
    }

    @Test
    public void testSuffixPathSpec()
    {
        ServletPathSpec spec = new ServletPathSpec("*.gz");
        assertEquals("Spec.pathSpec","*.gz",spec.getDeclaration());
        assertEquals("Spec.pathDepth",0,spec.getPathDepth());

        assertMatches(spec,"/downloads/distribution.tar.gz");
        assertMatches(spec,"/downloads/jetty.log.gz");

        assertNotMatches(spec,"/downloads/distribution.zip");
        assertNotMatches(spec,"/downloads/distribution.tgz");
        assertNotMatches(spec,"/abs/path");

        assertEquals("Spec.pathInfo",null,spec.getPathInfo("/downloads/distribution.tar.gz"));
    }
}
