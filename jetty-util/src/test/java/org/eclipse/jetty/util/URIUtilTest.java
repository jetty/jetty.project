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

package org.eclipse.jetty.util;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;


/* ------------------------------------------------------------ */
/** Util meta Tests.
 *
 */
public class URIUtilTest
{
    /* ------------------------------------------------------------ */
    @Test
    public void testEncodePath()
    {
        // test basic encode/decode
        StringBuilder buf = new StringBuilder();

        buf.setLength(0);
        URIUtil.encodePath(buf,"/foo%23+;,:=/b a r/?info ");
        assertEquals("/foo%2523+%3B,:=/b%20a%20r/%3Finfo%20",buf.toString());

        assertEquals("/foo%2523+%3B,:=/b%20a%20r/%3Finfo%20",URIUtil.encodePath("/foo%23+;,:=/b a r/?info "));

        buf.setLength(0);
        URIUtil.encodeString(buf,"foo%23;,:=b a r",";,= ");
        assertEquals("foo%2523%3b%2c:%3db%20a%20r",buf.toString());

        buf.setLength(0);
        URIUtil.encodePath(buf,"/context/'list'/\"me\"/;<script>window.alert('xss');</script>");
        assertEquals("/context/%27list%27/%22me%22/%3B%3Cscript%3Ewindow.alert(%27xss%27)%3B%3C/script%3E", buf.toString());

        buf.setLength(0);
        URIUtil.encodePath(buf, "test\u00f6?\u00f6:\u00df");
        assertEquals("test%C3%B6%3F%C3%B6:%C3%9F", buf.toString());

        buf.setLength(0);
        URIUtil.encodePath(buf, "test?\u00f6?\u00f6:\u00df");
        assertEquals("test%3F%C3%B6%3F%C3%B6:%C3%9F", buf.toString());
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testDecodePath()
    {
        assertEquals("/foo/bar",URIUtil.decodePath("xx/foo/barxx",2,8));
        assertEquals("/foo/bar",URIUtil.decodePath("/foo/bar"));
        assertEquals("/f o/b r",URIUtil.decodePath("/f%20o/b%20r"));
        assertEquals("/foo/bar",URIUtil.decodePath("/foo;ignore/bar;ignore"));
        assertEquals("/fää/bar",URIUtil.decodePath("/f\u00e4\u00e4;ignore/bar;ignore"));
        assertEquals("/f\u0629\u0629%23/bar",URIUtil.decodePath("/f%d8%a9%d8%a9%2523;ignore/bar;ignore"));
        
        assertEquals("foo%23;,:=b a r",URIUtil.decodePath("foo%2523%3b%2c:%3db%20a%20r;rubbish"));
        assertEquals("/foo/bar%23;,:=b a r=",URIUtil.decodePath("xxx/foo/bar%2523%3b%2c:%3db%20a%20r%3Dxxx;rubbish",3,35));
        assertEquals("f\u00e4\u00e4%23;,:=b a r=", URIUtil.decodePath("fää%2523%3b%2c:%3db%20a%20r%3D"));
        assertEquals("f\u0629\u0629%23;,:=b a r",URIUtil.decodePath("f%d8%a9%d8%a9%2523%3b%2c:%3db%20a%20r"));
        
        // Test for null character (real world ugly test case)
        byte oddBytes[] = { '/', 0x00, '/' };
        String odd = new String(oddBytes, StandardCharsets.ISO_8859_1);
        assertEquals(odd,URIUtil.decodePath("/%00/"));
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testAddPaths()
    {
        assertEquals("null+null", URIUtil.addPaths(null,null),null);
        assertEquals("null+", URIUtil.addPaths(null,""),"");
        assertEquals("null+bbb", URIUtil.addPaths(null,"bbb"),"bbb");
        assertEquals("null+/", URIUtil.addPaths(null,"/"),"/");
        assertEquals("null+/bbb", URIUtil.addPaths(null,"/bbb"),"/bbb");

        assertEquals("+null", URIUtil.addPaths("",null),"");
        assertEquals("+", URIUtil.addPaths("",""),"");
        assertEquals("+bbb", URIUtil.addPaths("","bbb"),"bbb");
        assertEquals("+/", URIUtil.addPaths("","/"),"/");
        assertEquals("+/bbb", URIUtil.addPaths("","/bbb"),"/bbb");

        assertEquals("aaa+null", URIUtil.addPaths("aaa",null),"aaa");
        assertEquals("aaa+", URIUtil.addPaths("aaa",""),"aaa");
        assertEquals("aaa+bbb", URIUtil.addPaths("aaa","bbb"),"aaa/bbb");
        assertEquals("aaa+/", URIUtil.addPaths("aaa","/"),"aaa/");
        assertEquals("aaa+/bbb", URIUtil.addPaths("aaa","/bbb"),"aaa/bbb");

        assertEquals("/+null", URIUtil.addPaths("/",null),"/");
        assertEquals("/+", URIUtil.addPaths("/",""),"/");
        assertEquals("/+bbb", URIUtil.addPaths("/","bbb"),"/bbb");
        assertEquals("/+/", URIUtil.addPaths("/","/"),"/");
        assertEquals("/+/bbb", URIUtil.addPaths("/","/bbb"),"/bbb");

        assertEquals("aaa/+null", URIUtil.addPaths("aaa/",null),"aaa/");
        assertEquals("aaa/+", URIUtil.addPaths("aaa/",""),"aaa/");
        assertEquals("aaa/+bbb", URIUtil.addPaths("aaa/","bbb"),"aaa/bbb");
        assertEquals("aaa/+/", URIUtil.addPaths("aaa/","/"),"aaa/");
        assertEquals("aaa/+/bbb", URIUtil.addPaths("aaa/","/bbb"),"aaa/bbb");

        assertEquals(";JS+null", URIUtil.addPaths(";JS",null),";JS");
        assertEquals(";JS+", URIUtil.addPaths(";JS",""),";JS");
        assertEquals(";JS+bbb", URIUtil.addPaths(";JS","bbb"),"bbb;JS");
        assertEquals(";JS+/", URIUtil.addPaths(";JS","/"),"/;JS");
        assertEquals(";JS+/bbb", URIUtil.addPaths(";JS","/bbb"),"/bbb;JS");

        assertEquals("aaa;JS+null", URIUtil.addPaths("aaa;JS",null),"aaa;JS");
        assertEquals("aaa;JS+", URIUtil.addPaths("aaa;JS",""),"aaa;JS");
        assertEquals("aaa;JS+bbb", URIUtil.addPaths("aaa;JS","bbb"),"aaa/bbb;JS");
        assertEquals("aaa;JS+/", URIUtil.addPaths("aaa;JS","/"),"aaa/;JS");
        assertEquals("aaa;JS+/bbb", URIUtil.addPaths("aaa;JS","/bbb"),"aaa/bbb;JS");

        assertEquals("aaa;JS+null", URIUtil.addPaths("aaa/;JS",null),"aaa/;JS");
        assertEquals("aaa;JS+", URIUtil.addPaths("aaa/;JS",""),"aaa/;JS");
        assertEquals("aaa;JS+bbb", URIUtil.addPaths("aaa/;JS","bbb"),"aaa/bbb;JS");
        assertEquals("aaa;JS+/", URIUtil.addPaths("aaa/;JS","/"),"aaa/;JS");
        assertEquals("aaa;JS+/bbb", URIUtil.addPaths("aaa/;JS","/bbb"),"aaa/bbb;JS");

        assertEquals("?A=1+null", URIUtil.addPaths("?A=1",null),"?A=1");
        assertEquals("?A=1+", URIUtil.addPaths("?A=1",""),"?A=1");
        assertEquals("?A=1+bbb", URIUtil.addPaths("?A=1","bbb"),"bbb?A=1");
        assertEquals("?A=1+/", URIUtil.addPaths("?A=1","/"),"/?A=1");
        assertEquals("?A=1+/bbb", URIUtil.addPaths("?A=1","/bbb"),"/bbb?A=1");

        assertEquals("aaa?A=1+null", URIUtil.addPaths("aaa?A=1",null),"aaa?A=1");
        assertEquals("aaa?A=1+", URIUtil.addPaths("aaa?A=1",""),"aaa?A=1");
        assertEquals("aaa?A=1+bbb", URIUtil.addPaths("aaa?A=1","bbb"),"aaa/bbb?A=1");
        assertEquals("aaa?A=1+/", URIUtil.addPaths("aaa?A=1","/"),"aaa/?A=1");
        assertEquals("aaa?A=1+/bbb", URIUtil.addPaths("aaa?A=1","/bbb"),"aaa/bbb?A=1");

        assertEquals("aaa?A=1+null", URIUtil.addPaths("aaa/?A=1",null),"aaa/?A=1");
        assertEquals("aaa?A=1+", URIUtil.addPaths("aaa/?A=1",""),"aaa/?A=1");
        assertEquals("aaa?A=1+bbb", URIUtil.addPaths("aaa/?A=1","bbb"),"aaa/bbb?A=1");
        assertEquals("aaa?A=1+/", URIUtil.addPaths("aaa/?A=1","/"),"aaa/?A=1");
        assertEquals("aaa?A=1+/bbb", URIUtil.addPaths("aaa/?A=1","/bbb"),"aaa/bbb?A=1");

        assertEquals(";JS?A=1+null", URIUtil.addPaths(";JS?A=1",null),";JS?A=1");
        assertEquals(";JS?A=1+", URIUtil.addPaths(";JS?A=1",""),";JS?A=1");
        assertEquals(";JS?A=1+bbb", URIUtil.addPaths(";JS?A=1","bbb"),"bbb;JS?A=1");
        assertEquals(";JS?A=1+/", URIUtil.addPaths(";JS?A=1","/"),"/;JS?A=1");
        assertEquals(";JS?A=1+/bbb", URIUtil.addPaths(";JS?A=1","/bbb"),"/bbb;JS?A=1");

        assertEquals("aaa;JS?A=1+null", URIUtil.addPaths("aaa;JS?A=1",null),"aaa;JS?A=1");
        assertEquals("aaa;JS?A=1+", URIUtil.addPaths("aaa;JS?A=1",""),"aaa;JS?A=1");
        assertEquals("aaa;JS?A=1+bbb", URIUtil.addPaths("aaa;JS?A=1","bbb"),"aaa/bbb;JS?A=1");
        assertEquals("aaa;JS?A=1+/", URIUtil.addPaths("aaa;JS?A=1","/"),"aaa/;JS?A=1");
        assertEquals("aaa;JS?A=1+/bbb", URIUtil.addPaths("aaa;JS?A=1","/bbb"),"aaa/bbb;JS?A=1");

        assertEquals("aaa;JS?A=1+null", URIUtil.addPaths("aaa/;JS?A=1",null),"aaa/;JS?A=1");
        assertEquals("aaa;JS?A=1+", URIUtil.addPaths("aaa/;JS?A=1",""),"aaa/;JS?A=1");
        assertEquals("aaa;JS?A=1+bbb", URIUtil.addPaths("aaa/;JS?A=1","bbb"),"aaa/bbb;JS?A=1");
        assertEquals("aaa;JS?A=1+/", URIUtil.addPaths("aaa/;JS?A=1","/"),"aaa/;JS?A=1");
        assertEquals("aaa;JS?A=1+/bbb", URIUtil.addPaths("aaa/;JS?A=1","/bbb"),"aaa/bbb;JS?A=1");

    }

    /* ------------------------------------------------------------ */
    @Test
    public void testCompactPath()
    {
        assertEquals("/foo/bar", URIUtil.compactPath("/foo/bar"));
        assertEquals("/foo/bar?a=b//c", URIUtil.compactPath("/foo/bar?a=b//c"));

        assertEquals("/foo/bar", URIUtil.compactPath("//foo//bar"));
        assertEquals("/foo/bar?a=b//c", URIUtil.compactPath("//foo//bar?a=b//c"));

        assertEquals("/foo/bar", URIUtil.compactPath("/foo///bar"));
        assertEquals("/foo/bar?a=b//c", URIUtil.compactPath("/foo///bar?a=b//c"));
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testParentPath()
    {
        assertEquals("parent /aaa/bbb/","/aaa/", URIUtil.parentPath("/aaa/bbb/"));
        assertEquals("parent /aaa/bbb","/aaa/", URIUtil.parentPath("/aaa/bbb"));
        assertEquals("parent /aaa/","/", URIUtil.parentPath("/aaa/"));
        assertEquals("parent /aaa","/", URIUtil.parentPath("/aaa"));
        assertEquals("parent /",null, URIUtil.parentPath("/"));
        assertEquals("parent null",null, URIUtil.parentPath(null));

    }
    /* ------------------------------------------------------------ */
    @Test
    public void testEqualsIgnoreEncoding()
    {
        assertTrue(URIUtil.equalsIgnoreEncodings("http://example.com/foo/bar","http://example.com/foo/bar" ));
        assertTrue(URIUtil.equalsIgnoreEncodings("/barry's","/barry%27s"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/barry%27s","/barry's"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/barry%27s","/barry%27s"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/b rry's","/b%20rry%27s"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/b rry%27s","/b%20rry's"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/b rry%27s","/b%20rry%27s"));
        
        assertTrue(URIUtil.equalsIgnoreEncodings("/foo%2fbar","/foo%2fbar"));
        assertTrue(URIUtil.equalsIgnoreEncodings("/foo%2fbar","/foo%2Fbar"));
        
        assertFalse(URIUtil.equalsIgnoreEncodings("ABC", "abc"));
        assertFalse(URIUtil.equalsIgnoreEncodings("/barry's","/barry%26s"));
        
        assertFalse(URIUtil.equalsIgnoreEncodings("/foo/bar","/foo%2fbar"));
        assertFalse(URIUtil.equalsIgnoreEncodings("/foo2fbar","/foo/bar"));
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testCanonicalPath()
    {
        String[][] canonical =
        {
            {"/aaa/bbb/","/aaa/bbb/"},
            {"/aaa//bbb/","/aaa//bbb/"},
            {"/aaa///bbb/","/aaa///bbb/"},
            {"/aaa/./bbb/","/aaa/bbb/"},
            {"/aaa/../bbb/","/bbb/"},
            {"/aaa/./../bbb/","/bbb/"},
            {"/aaa/bbb/ccc/../../ddd/","/aaa/ddd/"},
            {"./bbb/","bbb/"},
            {"./aaa/../bbb/","bbb/"},
            {"./",""},
            {".//",".//"},
            {".///",".///"},
            {"/.","/"},
            {"//.","//"},
            {"///.","///"},
            {"/","/"},
            {"aaa/bbb","aaa/bbb"},
            {"aaa/","aaa/"},
            {"aaa","aaa"},
            {"/aaa/bbb","/aaa/bbb"},
            {"/aaa//bbb","/aaa//bbb"},
            {"/aaa/./bbb","/aaa/bbb"},
            {"/aaa/../bbb","/bbb"},
            {"/aaa/./../bbb","/bbb"},
            {"./bbb","bbb"},
            {"./aaa/../bbb","bbb"},
            {"aaa/bbb/..","aaa/"},
            {"aaa/bbb/../","aaa/"},
            {"/aaa//../bbb","/aaa/bbb"},
            {"/aaa/./../bbb","/bbb"},
            {"./",""},
            {".",""},
            {"",""},
            {"..",null},
            {"./..",null},
            {"aaa/../..",null},
            {"/foo/bar/../../..",null},
            {"/../foo",null},
            {"/foo/.","/foo/"},
            {"a","a"},
            {"a/","a/"},
            {"a/.","a/"},
            {"a/..",""},
            {"a/../..",null},
            {"/foo/../../bar",null},
            {"/foo/../bar//","/bar//"},
        };

        for (int t=0;t<canonical.length;t++)
            assertEquals( "canonical "+canonical[t][0],
                          canonical[t][1],
                          URIUtil.canonicalPath(canonical[t][0])
                          );

    }


    /* ------------------------------------------------------------ */
    @Test
    public void testJarSource() throws Exception
    {
        assertThat(URIUtil.getJarSource("file:///tmp/"),is("file:///tmp/"));
        assertThat(URIUtil.getJarSource("jar:file:///tmp/foo.jar"),is("file:///tmp/foo.jar"));
        assertThat(URIUtil.getJarSource("jar:file:///tmp/foo.jar!/some/path"),is("file:///tmp/foo.jar"));
        assertThat(URIUtil.getJarSource(new URI("file:///tmp/")),is(new URI("file:///tmp/")));
        assertThat(URIUtil.getJarSource(new URI("jar:file:///tmp/foo.jar")),is(new URI("file:///tmp/foo.jar")));
        assertThat(URIUtil.getJarSource(new URI("jar:file:///tmp/foo.jar!/some/path")),is(new URI("file:///tmp/foo.jar")));
    }
}
