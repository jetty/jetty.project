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

package org.eclipse.jetty.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 *
 */
public class PathMapTest
{
    @Test
    public void testPathMap() throws Exception
    {
        PathMap<String> p = new PathMap<>();

        p.put("/abs/path", "1");
        p.put("/abs/path/longer", "2");
        p.put("/animal/bird/*", "3");
        p.put("/animal/fish/*", "4");
        p.put("/animal/*", "5");
        p.put("*.tar.gz", "6");
        p.put("*.gz", "7");
        p.put("/", "8");
        p.put("/XXX:/YYY", "9");
        p.put("", "10");
        p.put("/\u20ACuro/*", "11");

        String[][] tests = {
                { "/abs/path", "1"},
                { "/abs/path/xxx", "8"},
                { "/abs/pith", "8"},
                { "/abs/path/longer", "2"},
                { "/abs/path/", "8"},
                { "/abs/path/xxx", "8"},
                { "/animal/bird/eagle/bald", "3"},
                { "/animal/fish/shark/grey", "4"},
                { "/animal/insect/bug", "5"},
                { "/animal", "5"},
                { "/animal/", "5"},
                { "/animal/x", "5"},
                { "/animal/*", "5"},
                { "/suffix/path.tar.gz", "6"},
                { "/suffix/path.gz", "7"},
                { "/animal/path.gz", "5"},
                { "/Other/path", "8"},
                { "/\u20ACuro/path", "11"},
                { "/", "10"},
                };

        for (String[] test : tests)
        {
            assertEquals(test[0], test[1], p.getMatch(test[0]).getValue());
        }

        assertEquals("Get absolute path", "1", p.get("/abs/path"));
        assertEquals("Match absolute path", "/abs/path", p.getMatch("/abs/path").getKey());
        assertEquals("all matches", "[/animal/bird/*=3, /animal/*=5, *.tar.gz=6, *.gz=7, /=8]",
                p.getMatches("/animal/bird/path.tar.gz").toString());
        assertEquals("Dir matches", "[/animal/fish/*=4, /animal/*=5, /=8]", p.getMatches("/animal/fish/").toString());
        assertEquals("Dir matches", "[/animal/fish/*=4, /animal/*=5, /=8]", p.getMatches("/animal/fish").toString());
        assertEquals("Root matches", "[=10, /=8]",p.getMatches("/").toString());
        assertEquals("Dir matches", "[/=8]", p.getMatches("").toString());

        assertEquals("pathMatch exact", "/Foo/bar", PathMap.pathMatch("/Foo/bar", "/Foo/bar"));
        assertEquals("pathMatch prefix", "/Foo", PathMap.pathMatch("/Foo/*", "/Foo/bar"));
        assertEquals("pathMatch prefix", "/Foo", PathMap.pathMatch("/Foo/*", "/Foo/"));
        assertEquals("pathMatch prefix", "/Foo", PathMap.pathMatch("/Foo/*", "/Foo"));
        assertEquals("pathMatch suffix", "/Foo/bar.ext", PathMap.pathMatch("*.ext", "/Foo/bar.ext"));
        assertEquals("pathMatch default", "/Foo/bar.ext", PathMap.pathMatch("/", "/Foo/bar.ext"));

        assertEquals("pathInfo exact", null, PathMap.pathInfo("/Foo/bar", "/Foo/bar"));
        assertEquals("pathInfo prefix", "/bar", PathMap.pathInfo("/Foo/*", "/Foo/bar"));
        assertEquals("pathInfo prefix", "/*", PathMap.pathInfo("/Foo/*", "/Foo/*"));
        assertEquals("pathInfo prefix", "/", PathMap.pathInfo("/Foo/*", "/Foo/"));
        assertEquals("pathInfo prefix", null, PathMap.pathInfo("/Foo/*", "/Foo"));
        assertEquals("pathInfo suffix", null, PathMap.pathInfo("*.ext", "/Foo/bar.ext"));
        assertEquals("pathInfo default", null, PathMap.pathInfo("/", "/Foo/bar.ext"));
        assertEquals("multi paths", "9", p.getMatch("/XXX").getValue());
        assertEquals("multi paths", "9", p.getMatch("/YYY").getValue());

        p.put("/*", "0");

        assertEquals("Get absolute path", "1", p.get("/abs/path"));
        assertEquals("Match absolute path", "/abs/path", p.getMatch("/abs/path").getKey());
        assertEquals("Match absolute path", "1", p.getMatch("/abs/path").getValue());
        assertEquals("Mismatch absolute path", "0", p.getMatch("/abs/path/xxx").getValue());
        assertEquals("Mismatch absolute path", "0", p.getMatch("/abs/pith").getValue());
        assertEquals("Match longer absolute path", "2", p.getMatch("/abs/path/longer").getValue());
        assertEquals("Not exact absolute path", "0", p.getMatch("/abs/path/").getValue());
        assertEquals("Not exact absolute path", "0", p.getMatch("/abs/path/xxx").getValue());

        assertEquals("Match longest prefix", "3", p.getMatch("/animal/bird/eagle/bald").getValue());
        assertEquals("Match longest prefix", "4", p.getMatch("/animal/fish/shark/grey").getValue());
        assertEquals("Match longest prefix", "5", p.getMatch("/animal/insect/bug").getValue());
        assertEquals("mismatch exact prefix", "5", p.getMatch("/animal").getValue());
        assertEquals("mismatch exact prefix", "5", p.getMatch("/animal/").getValue());

        assertEquals("Match longest suffix", "0", p.getMatch("/suffix/path.tar.gz").getValue());
        assertEquals("Match longest suffix", "0", p.getMatch("/suffix/path.gz").getValue());
        assertEquals("prefix rather than suffix", "5", p.getMatch("/animal/path.gz").getValue());

        assertEquals("default", "0", p.getMatch("/Other/path").getValue());

        assertEquals("pathMatch /*", "", PathMap.pathMatch("/*", "/xxx/zzz"));
        assertEquals("pathInfo /*", "/xxx/zzz", PathMap.pathInfo("/*", "/xxx/zzz"));

        assertTrue("match /", PathMap.match("/", "/anything"));
        assertTrue("match /*", PathMap.match("/*", "/anything"));
        assertTrue("match /foo", PathMap.match("/foo", "/foo"));
        assertTrue("!match /foo", !PathMap.match("/foo", "/bar"));
        assertTrue("match /foo/*", PathMap.match("/foo/*", "/foo"));
        assertTrue("match /foo/*", PathMap.match("/foo/*", "/foo/"));
        assertTrue("match /foo/*", PathMap.match("/foo/*", "/foo/anything"));
        assertTrue("!match /foo/*", !PathMap.match("/foo/*", "/bar"));
        assertTrue("!match /foo/*", !PathMap.match("/foo/*", "/bar/"));
        assertTrue("!match /foo/*", !PathMap.match("/foo/*", "/bar/anything"));
        assertTrue("match *.foo", PathMap.match("*.foo", "anything.foo"));
        assertTrue("!match *.foo", !PathMap.match("*.foo", "anything.bar"));

        assertEquals("match / with ''", "10", p.getMatch("/").getValue());
        
        assertTrue("match \"\"", PathMap.match("", "/"));
    }

    /**
     * See JIRA issue: JETTY-88.
     * @throws Exception failed test
     */
    @Test
    public void testPathMappingsOnlyMatchOnDirectoryNames() throws Exception
    {
        String spec = "/xyz/*";

        assertMatch(spec, "/xyz");
        assertMatch(spec, "/xyz/");
        assertMatch(spec, "/xyz/123");
        assertMatch(spec, "/xyz/123/");
        assertMatch(spec, "/xyz/123.txt");
        assertNotMatch(spec, "/xyz123");
        assertNotMatch(spec, "/xyz123;jessionid=99");
        assertNotMatch(spec, "/xyz123/");
        assertNotMatch(spec, "/xyz123/456");
        assertNotMatch(spec, "/xyz.123");
        assertNotMatch(spec, "/xyz;123"); // as if the ; was encoded and part of the path
        assertNotMatch(spec, "/xyz?123"); // as if the ? was encoded and part of the path
    }

    @Test
    public void testPrecidenceVsOrdering() throws Exception
    {
        PathMap<String> p = new PathMap<>();
        p.put("/dump/gzip/*","prefix");
        p.put("*.txt","suffix");
       
        assertEquals(null,p.getMatch("/foo/bar"));
        assertEquals("prefix",p.getMatch("/dump/gzip/something").getValue());
        assertEquals("suffix",p.getMatch("/foo/something.txt").getValue());
        assertEquals("prefix",p.getMatch("/dump/gzip/something.txt").getValue());
        
        p = new PathMap<>();
        p.put("*.txt","suffix");
        p.put("/dump/gzip/*","prefix");
       
        assertEquals(null,p.getMatch("/foo/bar"));
        assertEquals("prefix",p.getMatch("/dump/gzip/something").getValue());
        assertEquals("suffix",p.getMatch("/foo/something.txt").getValue());
        assertEquals("prefix",p.getMatch("/dump/gzip/something.txt").getValue());
    }
    
    
    
    private void assertMatch(String spec, String path)
    {
        boolean match = PathMap.match(spec, path);
        assertTrue("PathSpec '" + spec + "' should match path '" + path + "'", match);
    }

    private void assertNotMatch(String spec, String path)
    {
        boolean match = PathMap.match(spec, path);
        assertFalse("PathSpec '" + spec + "' should not match path '" + path + "'", match);
    }
}
