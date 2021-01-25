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

package org.eclipse.jetty.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        p.put("/\u20ACuro/*", "11");

        String[][] tests = {
            {"/abs/path", "1"},
            {"/abs/path/xxx", "8"},
            {"/abs/pith", "8"},
            {"/abs/path/longer", "2"},
            {"/abs/path/", "8"},
            {"/abs/path/xxx", "8"},
            {"/animal/bird/eagle/bald", "3"},
            {"/animal/fish/shark/grey", "4"},
            {"/animal/insect/bug", "5"},
            {"/animal", "5"},
            {"/animal/", "5"},
            {"/animal/x", "5"},
            {"/animal/*", "5"},
            {"/suffix/path.tar.gz", "6"},
            {"/suffix/path.gz", "7"},
            {"/animal/path.gz", "5"},
            {"/Other/path", "8"},
            // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
            {"/\u20ACuro/path", "11"},
            {"/", "10"}
        };

        for (String[] test : tests)
        {
            assertEquals(test[1], p.getMatch(test[0]).getValue(), test[0]);
        }

        assertEquals("1", p.get("/abs/path"), "Get absolute path");
        assertEquals("/abs/path", p.getMatch("/abs/path").getKey(), "Match absolute path");
        assertEquals("[/animal/bird/*=3, /animal/*=5, *.tar.gz=6, *.gz=7, /=8]", p.getMatches("/animal/bird/path.tar.gz").toString(), "all matches");
        assertEquals("[/animal/fish/*=4, /animal/*=5, /=8]", p.getMatches("/animal/fish/").toString(), "Dir matches");
        assertEquals("[/animal/fish/*=4, /animal/*=5, /=8]", p.getMatches("/animal/fish").toString(), "Dir matches");
        assertEquals("[=10, /=8]", p.getMatches("/").toString(), "Root matches");
        assertEquals("[/=8]", p.getMatches("").toString(), "Dir matches");

        assertEquals("/Foo/bar", PathMap.pathMatch("/Foo/bar", "/Foo/bar"), "pathMatch exact");
        assertEquals("/Foo", PathMap.pathMatch("/Foo/*", "/Foo/bar"), "pathMatch prefix");
        assertEquals("/Foo", PathMap.pathMatch("/Foo/*", "/Foo/"), "pathMatch prefix");
        assertEquals("/Foo", PathMap.pathMatch("/Foo/*", "/Foo"), "pathMatch prefix");
        assertEquals("/Foo/bar.ext", PathMap.pathMatch("*.ext", "/Foo/bar.ext"), "pathMatch suffix");
        assertEquals("/Foo/bar.ext", PathMap.pathMatch("/", "/Foo/bar.ext"), "pathMatch default");

        assertEquals(null, PathMap.pathInfo("/Foo/bar", "/Foo/bar"), "pathInfo exact");
        assertEquals("/bar", PathMap.pathInfo("/Foo/*", "/Foo/bar"), "pathInfo prefix");
        assertEquals("/*", PathMap.pathInfo("/Foo/*", "/Foo/*"), "pathInfo prefix");
        assertEquals("/", PathMap.pathInfo("/Foo/*", "/Foo/"), "pathInfo prefix");
        assertEquals(null, PathMap.pathInfo("/Foo/*", "/Foo"), "pathInfo prefix");
        assertEquals(null, PathMap.pathInfo("*.ext", "/Foo/bar.ext"), "pathInfo suffix");
        assertEquals(null, PathMap.pathInfo("/", "/Foo/bar.ext"), "pathInfo default");
        assertEquals("9", p.getMatch("/XXX").getValue(), "multi paths");
        assertEquals("9", p.getMatch("/YYY").getValue(), "multi paths");

        p.put("/*", "0");

        assertEquals("1", p.get("/abs/path"), "Get absolute path");
        assertEquals("/abs/path", p.getMatch("/abs/path").getKey(), "Match absolute path");
        assertEquals("1", p.getMatch("/abs/path").getValue(), "Match absolute path");
        assertEquals("0", p.getMatch("/abs/path/xxx").getValue(), "Mismatch absolute path");
        assertEquals("0", p.getMatch("/abs/pith").getValue(), "Mismatch absolute path");
        assertEquals("2", p.getMatch("/abs/path/longer").getValue(), "Match longer absolute path");
        assertEquals("0", p.getMatch("/abs/path/").getValue(), "Not exact absolute path");
        assertEquals("0", p.getMatch("/abs/path/xxx").getValue(), "Not exact absolute path");

        assertEquals("3", p.getMatch("/animal/bird/eagle/bald").getValue(), "Match longest prefix");
        assertEquals("4", p.getMatch("/animal/fish/shark/grey").getValue(), "Match longest prefix");
        assertEquals("5", p.getMatch("/animal/insect/bug").getValue(), "Match longest prefix");
        assertEquals("5", p.getMatch("/animal").getValue(), "mismatch exact prefix");
        assertEquals("5", p.getMatch("/animal/").getValue(), "mismatch exact prefix");

        assertEquals("0", p.getMatch("/suffix/path.tar.gz").getValue(), "Match longest suffix");
        assertEquals("0", p.getMatch("/suffix/path.gz").getValue(), "Match longest suffix");
        assertEquals("5", p.getMatch("/animal/path.gz").getValue(), "prefix rather than suffix");

        assertEquals("0", p.getMatch("/Other/path").getValue(), "default");

        assertEquals("", PathMap.pathMatch("/*", "/xxx/zzz"), "pathMatch /*");
        assertEquals("/xxx/zzz", PathMap.pathInfo("/*", "/xxx/zzz"), "pathInfo /*");

        assertTrue(PathMap.match("/", "/anything"), "match /");
        assertTrue(PathMap.match("/*", "/anything"), "match /*");
        assertTrue(PathMap.match("/foo", "/foo"), "match /foo");
        assertTrue(!PathMap.match("/foo", "/bar"), "!match /foo");
        assertTrue(PathMap.match("/foo/*", "/foo"), "match /foo/*");
        assertTrue(PathMap.match("/foo/*", "/foo/"), "match /foo/*");
        assertTrue(PathMap.match("/foo/*", "/foo/anything"), "match /foo/*");
        assertTrue(!PathMap.match("/foo/*", "/bar"), "!match /foo/*");
        assertTrue(!PathMap.match("/foo/*", "/bar/"), "!match /foo/*");
        assertTrue(!PathMap.match("/foo/*", "/bar/anything"), "!match /foo/*");
        assertTrue(PathMap.match("*.foo", "anything.foo"), "match *.foo");
        assertTrue(!PathMap.match("*.foo", "anything.bar"), "!match *.foo");

        assertEquals("10", p.getMatch("/").getValue(), "match / with ''");

        assertTrue(PathMap.match("", "/"), "match \"\"");
    }

    /**
     * See JIRA issue: JETTY-88.
     *
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
        p.put("/dump/gzip/*", "prefix");
        p.put("*.txt", "suffix");

        assertEquals(null, p.getMatch("/foo/bar"));
        assertEquals("prefix", p.getMatch("/dump/gzip/something").getValue());
        assertEquals("suffix", p.getMatch("/foo/something.txt").getValue());
        assertEquals("prefix", p.getMatch("/dump/gzip/something.txt").getValue());

        p = new PathMap<>();
        p.put("*.txt", "suffix");
        p.put("/dump/gzip/*", "prefix");

        assertEquals(null, p.getMatch("/foo/bar"));
        assertEquals("prefix", p.getMatch("/dump/gzip/something").getValue());
        assertEquals("suffix", p.getMatch("/foo/something.txt").getValue());
        assertEquals("prefix", p.getMatch("/dump/gzip/something.txt").getValue());
    }

    private void assertMatch(String spec, String path)
    {
        boolean match = PathMap.match(spec, path);
        assertTrue(match, "PathSpec '" + spec + "' should match path '" + path + "'");
    }

    private void assertNotMatch(String spec, String path)
    {
        boolean match = PathMap.match(spec, path);
        assertFalse(match, "PathSpec '" + spec + "' should not match path '" + path + "'");
    }
}
