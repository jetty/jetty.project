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

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assert;
import org.junit.Test;

public class PathMappingsTest
{
    private void assertMatch(PathMappings<String> pathmap, String path, String expectedValue)
    {
        String msg = String.format(".getMatch(\"%s\")",path);
        MappedResource<String> match = pathmap.getMatch(path);
        Assert.assertThat(msg,match,notNullValue());
        String actualMatch = match.getResource();
        Assert.assertEquals(msg,expectedValue,actualMatch);
    }

    public void dumpMappings(PathMappings<String> p)
    {
        for (MappedResource<String> res : p)
        {
            System.out.printf("  %s%n",res);
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

        p.put(new ServletPathSpec("/"),"default");
        p.put(new ServletPathSpec("/animal/bird/*"),"birds");
        p.put(new ServletPathSpec("/animal/fish/*"),"fishes");
        p.put(new ServletPathSpec("/animal/*"),"animals");
        p.put(new RegexPathSpec("^/animal/.*/chat$"),"animalChat");
        p.put(new RegexPathSpec("^/animal/.*/cam$"),"animalCam");
        p.put(new RegexPathSpec("^/entrance/cam$"),"entranceCam");

        // dumpMappings(p);

        assertMatch(p,"/animal/bird/eagle","birds");
        assertMatch(p,"/animal/fish/bass/sea","fishes");
        assertMatch(p,"/animal/peccary/javalina/evolution","animals");
        assertMatch(p,"/","default");
        assertMatch(p,"/animal/bird/eagle/chat","animalChat");
        assertMatch(p,"/animal/bird/penguin/chat","animalChat");
        assertMatch(p,"/animal/fish/trout/cam","animalCam");
        assertMatch(p,"/entrance/cam","entranceCam");
    }
    
    /**
     * Test the match order rules imposed by the Servlet API (default vs any)
     */
    @Test
    public void testServletMatchDefault()
    {
        PathMappings<String> p = new PathMappings<>();

        p.put(new ServletPathSpec("/"),"default");
        p.put(new ServletPathSpec("/*"),"any"); 

        assertMatch(p,"/abs/path","any");
        assertMatch(p,"/abs/path/xxx","any");
        assertMatch(p,"/animal/bird/eagle/bald","any");
        assertMatch(p,"/","any");
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

        p.put(new ServletPathSpec("/"),"default");
        p.put(new ServletPathSpec("/animal/bird/*"),"birds");
        p.put(new ServletPathSpec("/animal/fish/*"),"fishes");
        p.put(new ServletPathSpec("/animal/*"),"animals");
        p.put(new UriTemplatePathSpec("/animal/{type}/{name}/chat"),"animalChat");
        p.put(new UriTemplatePathSpec("/animal/{type}/{name}/cam"),"animalCam");
        p.put(new UriTemplatePathSpec("/entrance/cam"),"entranceCam");

        // dumpMappings(p);

        assertMatch(p,"/animal/bird/eagle","birds");
        assertMatch(p,"/animal/fish/bass/sea","fishes");
        assertMatch(p,"/animal/peccary/javalina/evolution","animals");
        assertMatch(p,"/","default");
        assertMatch(p,"/animal/bird/eagle/chat","animalChat");
        assertMatch(p,"/animal/bird/penguin/chat","animalChat");
        assertMatch(p,"/animal/fish/trout/cam","animalCam");
        assertMatch(p,"/entrance/cam","entranceCam");
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

        p.put(new UriTemplatePathSpec("/a/{var}/c"),"endpointA");
        p.put(new UriTemplatePathSpec("/a/b/c"),"endpointB");
        p.put(new UriTemplatePathSpec("/a/{var1}/{var2}"),"endpointC");
        p.put(new UriTemplatePathSpec("/{var1}/d"),"endpointD");
        p.put(new UriTemplatePathSpec("/b/{var2}"),"endpointE");

        // dumpMappings(p);

        assertMatch(p,"/a/b/c","endpointB");
        assertMatch(p,"/a/d/c","endpointA");
        assertMatch(p,"/a/x/y","endpointC");

        assertMatch(p,"/b/d","endpointE");
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

        assertEquals("pathMatch exact", "/Foo/bar", new ServletPathSpec("/Foo/bar").getPathMatch("/Foo/bar"));
        assertEquals("pathMatch prefix", "/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo/bar"));
        assertEquals("pathMatch prefix", "/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo/"));
        assertEquals("pathMatch prefix", "/Foo", new ServletPathSpec("/Foo/*").getPathMatch("/Foo"));
        assertEquals("pathMatch suffix", "/Foo/bar.ext", new ServletPathSpec("*.ext").getPathMatch("/Foo/bar.ext"));
        assertEquals("pathMatch default", "/Foo/bar.ext", new ServletPathSpec("/").getPathMatch("/Foo/bar.ext"));

        assertEquals("pathInfo exact", null, new ServletPathSpec("/Foo/bar").getPathInfo("/Foo/bar"));
        assertEquals("pathInfo prefix", "/bar", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/bar"));
        assertEquals("pathInfo prefix", "/*", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/*"));
        assertEquals("pathInfo prefix", "/", new ServletPathSpec("/Foo/*").getPathInfo("/Foo/"));
        assertEquals("pathInfo prefix", null, new ServletPathSpec("/Foo/*").getPathInfo("/Foo"));
        assertEquals("pathInfo suffix", null, new ServletPathSpec("*.ext").getPathInfo("/Foo/bar.ext"));
        assertEquals("pathInfo default", null, new ServletPathSpec("/").getPathInfo("/Foo/bar.ext"));
        
        p.put(new ServletPathSpec("/*"), "0");

        // assertEquals("Get absolute path", "1", p.get("/abs/path"));
        assertEquals("Match absolute path", "/abs/path", p.getMatch("/abs/path").getPathSpec().pathSpec);
        assertEquals("Match absolute path", "1", p.getMatch("/abs/path").getResource());
        assertEquals("Mismatch absolute path", "0", p.getMatch("/abs/path/xxx").getResource());
        assertEquals("Mismatch absolute path", "0", p.getMatch("/abs/pith").getResource());
        assertEquals("Match longer absolute path", "2", p.getMatch("/abs/path/longer").getResource());
        assertEquals("Not exact absolute path", "0", p.getMatch("/abs/path/").getResource());
        assertEquals("Not exact absolute path", "0", p.getMatch("/abs/path/xxx").getResource());

        assertEquals("Match longest prefix", "3", p.getMatch("/animal/bird/eagle/bald").getResource());
        assertEquals("Match longest prefix", "4", p.getMatch("/animal/fish/shark/grey").getResource());
        assertEquals("Match longest prefix", "5", p.getMatch("/animal/insect/bug").getResource());
        assertEquals("mismatch exact prefix", "5", p.getMatch("/animal").getResource());
        assertEquals("mismatch exact prefix", "5", p.getMatch("/animal/").getResource());

        assertEquals("Match longest suffix", "0", p.getMatch("/suffix/path.tar.gz").getResource());
        assertEquals("Match longest suffix", "0", p.getMatch("/suffix/path.gz").getResource());
        assertEquals("prefix rather than suffix", "5", p.getMatch("/animal/path.gz").getResource());

        assertEquals("default", "0", p.getMatch("/Other/path").getResource());

        assertEquals("pathMatch /*", "", new ServletPathSpec("/*").getPathMatch("/xxx/zzz"));
        assertEquals("pathInfo /*", "/xxx/zzz", new ServletPathSpec("/*").getPathInfo("/xxx/zzz"));

        assertTrue("match /", new ServletPathSpec("/").matches("/anything"));
        assertTrue("match /*", new ServletPathSpec("/*").matches("/anything"));
        assertTrue("match /foo", new ServletPathSpec("/foo").matches("/foo"));
        assertTrue("!match /foo", !new ServletPathSpec("/foo").matches("/bar"));
        assertTrue("match /foo/*", new ServletPathSpec("/foo/*").matches("/foo"));
        assertTrue("match /foo/*", new ServletPathSpec("/foo/*").matches("/foo/"));
        assertTrue("match /foo/*", new ServletPathSpec("/foo/*").matches("/foo/anything"));
        assertTrue("!match /foo/*", !new ServletPathSpec("/foo/*").matches("/bar"));
        assertTrue("!match /foo/*", !new ServletPathSpec("/foo/*").matches("/bar/"));
        assertTrue("!match /foo/*", !new ServletPathSpec("/foo/*").matches("/bar/anything"));
        assertTrue("match *.foo", new ServletPathSpec("*.foo").matches("anything.foo"));
        assertTrue("!match *.foo", !new ServletPathSpec("*.foo").matches("anything.bar"));

        assertEquals("match / with ''", "10", p.getMatch("/").getResource());
        
        assertTrue("match \"\"", new ServletPathSpec("").matches("/"));
    }

    /**
     * See JIRA issue: JETTY-88.
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
        p.put(new ServletPathSpec("/dump/gzip/*"),"prefix");
        p.put(new ServletPathSpec("*.txt"),"suffix");
       
        assertEquals(null,p.getMatch("/foo/bar"));
        assertEquals("prefix",p.getMatch("/dump/gzip/something").getResource());
        assertEquals("suffix",p.getMatch("/foo/something.txt").getResource());
        assertEquals("prefix",p.getMatch("/dump/gzip/something.txt").getResource());
        
        p = new PathMappings<>();
        p.put(new ServletPathSpec("*.txt"),"suffix");
        p.put(new ServletPathSpec("/dump/gzip/*"),"prefix");
       
        assertEquals(null,p.getMatch("/foo/bar"));
        assertEquals("prefix",p.getMatch("/dump/gzip/something").getResource());
        assertEquals("suffix",p.getMatch("/foo/something.txt").getResource());
        assertEquals("prefix",p.getMatch("/dump/gzip/something.txt").getResource());
    }
    
    @Test
    public void testBadPathSpecs()
    {
        try{new ServletPathSpec("*");Assert.fail();}catch(IllegalArgumentException e){}
        try{new ServletPathSpec("/foo/*/bar");Assert.fail();}catch(IllegalArgumentException e){}
        try{new ServletPathSpec("/foo*");Assert.fail();}catch(IllegalArgumentException e){}
        try{new ServletPathSpec("*/foo");Assert.fail();}catch(IllegalArgumentException e){}
        try{new ServletPathSpec("*.foo/*");Assert.fail();}catch(IllegalArgumentException e){}
    }
}
