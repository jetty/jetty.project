//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.Utf8Appendable;
import org.junit.Assert;
import org.junit.Test;


/* ------------------------------------------------------------ */
public class HttpURITest
{
    String[][] tests=
    {
        {"/path/to/context",null,null,"-1","/path/to/context",null,null,null},
        {"http://example.com/path/to/context;param?query=%22value%22#fragment","http","example.com","-1","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://[::1]/path/to/context;param?query=%22value%22#fragment","http","[::1]","-1","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://example.com:8080/path/to/context;param?query=%22value%22#fragment","http","example.com","8080","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://[::1]:8080/path/to/context;param?query=%22value%22#fragment","http","[::1]","8080","/path/to/context;param","param","query=%22value%22","fragment"},
    };
    
    public static int
    INPUT=0,SCHEME=1,HOST=2,PORT=3,PATH=4,PARAM=5,QUERY=6,FRAGMENT=7;

    /* ------------------------------------------------------------ */
    @Test
    public void testFromString() throws Exception
    {
        for (String[] test:tests)
        {
            HttpURI uri = new HttpURI(test[INPUT]);

            assertEquals(test[SCHEME], uri.getScheme());
            assertEquals(test[HOST], uri.getHost());
            assertEquals(Integer.parseInt(test[PORT]), uri.getPort());
            assertEquals(test[PATH], uri.getPath());
            assertEquals(test[PARAM], uri.getParam());
            assertEquals(test[QUERY], uri.getQuery());
            assertEquals(test[FRAGMENT], uri.getFragment());
        }
    }

    /* ------------------------------------------------------------ */
    @Test
    public void testFromURI() throws Exception
    {
        for (String[] test:tests)
        {
            HttpURI uri = new HttpURI(new URI(test[INPUT]));

            assertEquals(test[SCHEME], uri.getScheme());
            assertEquals(test[HOST], uri.getHost());
            assertEquals(Integer.parseInt(test[PORT]), uri.getPort());
            assertEquals(test[PATH], uri.getPath());
            assertEquals(test[PARAM], uri.getParam());
            assertEquals(test[QUERY], uri.getQuery());
            assertEquals(test[FRAGMENT], uri.getFragment());
        }
    }

    private final String[][] partial_tests=
    {
       /* 0*/ {"/path/info",null,null,null,null,"/path/info",null,null,null},
       /* 1*/ {"/path/info#fragment",null,null,null,null,"/path/info",null,null,"fragment"},
       /* 2*/ {"/path/info?query",null,null,null,null,"/path/info",null,"query",null},
       /* 3*/ {"/path/info?query#fragment",null,null,null,null,"/path/info",null,"query","fragment"},
       /* 4*/ {"/path/info;param",null,null,null,null,"/path/info;param","param",null,null},
       /* 5*/ {"/path/info;param#fragment",null,null,null,null,"/path/info;param","param",null,"fragment"},
       /* 6*/ {"/path/info;param?query",null,null,null,null,"/path/info;param","param","query",null},
       /* 7*/ {"/path/info;param?query#fragment",null,null,null,null,"/path/info;param","param","query","fragment"},
       /* 8*/ {"//host/path/info",null,"//host","host",null,"/path/info",null,null,null},
       /* 9*/ {"//user@host/path/info",null,"//user@host","host",null,"/path/info",null,null,null},
       /*10*/ {"//user@host:8080/path/info",null,"//user@host:8080","host","8080","/path/info",null,null,null},
       /*11*/ {"//host:8080/path/info",null,"//host:8080","host","8080","/path/info",null,null,null},
       /*12*/ {"http:/path/info","http",null,null,null,"/path/info",null,null,null},
       /*13*/ {"http:/path/info#fragment","http",null,null,null,"/path/info",null,null,"fragment"},
       /*14*/ {"http:/path/info?query","http",null,null,null,"/path/info",null,"query",null},
       /*15*/ {"http:/path/info?query#fragment","http",null,null,null,"/path/info",null,"query","fragment"},
       /*16*/ {"http:/path/info;param","http",null,null,null,"/path/info;param","param",null,null},
       /*17*/ {"http:/path/info;param#fragment","http",null,null,null,"/path/info;param","param",null,"fragment"},
       /*18*/ {"http:/path/info;param?query","http",null,null,null,"/path/info;param","param","query",null},
       /*19*/ {"http:/path/info;param?query#fragment","http",null,null,null,"/path/info;param","param","query","fragment"},
       /*20*/ {"http://user@host:8080/path/info;param?query#fragment","http","//user@host:8080","host","8080","/path/info;param","param","query","fragment"},
       /*21*/ {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","//user@host:8080","host","8080","/path/info;param","param","query","fragment"},
       /*22*/ {"http:///;?#","http","//",null,null,"/;","","",""},
       /*23*/ {"/path/info?a=?query",null,null,null,null,"/path/info",null,"a=?query",null},
       /*24*/ {"/path/info?a=;query",null,null,null,null,"/path/info",null,"a=;query",null},
       /*25*/ {"//host:8080//",null,"//host:8080","host","8080","//",null,null,null},
       /*26*/ {"file:///path/info","file","//",null,null,"/path/info",null,null,null},
       /*27*/ {"//",null,"//",null,null,null,null,null,null},
       /*28*/ {"/;param",null, null, null,null,"/;param", "param",null,null},
       /*29*/ {"/?x=y",null, null, null,null,"/", null,"x=y",null},
       /*30*/ {"/?abc=test",null, null, null,null,"/", null,"abc=test",null},
       /*31*/ {"/#fragment",null, null, null,null,"/", null,null,"fragment"},
       /*32*/ {"http://localhost:8080", "http", "//localhost:8080", "localhost", "8080", null, null, null, null},
       /*33*/ {"./?foo:bar=:1:1::::",null,null,null,null,"./",null,"foo:bar=:1:1::::",null}
    };

    @Test
    public void testPartialURIs() throws Exception
    {
        HttpURI uri = new HttpURI(true);

        for (int t=0;t<partial_tests.length;t++)
        {
            uri.parse(partial_tests[t][0]);
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][1],uri.getScheme());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][2],uri.getAuthority());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][3],uri.getHost());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][4]==null?-1:Integer.parseInt(partial_tests[t][4]),uri.getPort());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][5],uri.getPath());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][6],uri.getParam());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][7],uri.getQuery());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][8],uri.getFragment());
            assertEquals(partial_tests[t][0], uri.toString());
        }

    }

    private final String[][] path_tests=
    {
       /* 0*/ {"/path/info",null,null,null,null,"/path/info",null,null,null},
       /* 1*/ {"/path/info#fragment",null,null,null,null,"/path/info",null,null,"fragment"},
       /* 2*/ {"/path/info?query",null,null,null,null,"/path/info",null,"query",null},
       /* 3*/ {"/path/info?query#fragment",null,null,null,null,"/path/info",null,"query","fragment"},
       /* 4*/ {"/path/info;param",null,null,null,null,"/path/info;param","param",null,null},
       /* 5*/ {"/path/info;param#fragment",null,null,null,null,"/path/info;param","param",null,"fragment"},
       /* 6*/ {"/path/info;param?query",null,null,null,null,"/path/info;param","param","query",null},
       /* 7*/ {"/path/info;param?query#fragment",null,null,null,null,"/path/info;param","param","query","fragment"},
       /* 8*/ {"//host/path/info",null,null,null,null,"//host/path/info",null,null,null},
       /* 9*/ {"//user@host/path/info",null,null,null,null,"//user@host/path/info",null,null,null},
       /*10*/ {"//user@host:8080/path/info",null,null,null,null,"//user@host:8080/path/info",null,null,null},
       /*11*/ {"//host:8080/path/info",null,null,null,null,"//host:8080/path/info",null,null,null},
       /*12*/ {"http:/path/info","http",null,null,null,"/path/info",null,null,null},
       /*13*/ {"http:/path/info#fragment","http",null,null,null,"/path/info",null,null,"fragment"},
       /*14*/ {"http:/path/info?query","http",null,null,null,"/path/info",null,"query",null},
       /*15*/ {"http:/path/info?query#fragment","http",null,null,null,"/path/info",null,"query","fragment"},
       /*16*/ {"http:/path/info;param","http",null,null,null,"/path/info;param","param",null,null},
       /*17*/ {"http:/path/info;param#fragment","http",null,null,null,"/path/info;param","param",null,"fragment"},
       /*18*/ {"http:/path/info;param?query","http",null,null,null,"/path/info;param","param","query",null},
       /*19*/ {"http:/path/info;param?query#fragment","http",null,null,null,"/path/info;param","param","query","fragment"},
       /*20*/ {"http://user@host:8080/path/info;param?query#fragment","http","//user@host:8080","host","8080","/path/info;param","param","query","fragment"},
       /*21*/ {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","//user@host:8080","host","8080","/path/info;param","param","query","fragment"},
       /*22*/ {"http:///;?#","http","//",null,null,"/;","","",""},
       /*23*/ {"/path/info?a=?query",null,null,null,null,"/path/info",null,"a=?query",null},
       /*24*/ {"/path/info?a=;query",null,null,null,null,"/path/info",null,"a=;query",null},
       /*25*/ {"//host:8080//",null,null,null,null,"//host:8080//",null,null,null},
       /*26*/ {"file:///path/info","file","//",null,null,"/path/info",null,null,null},
       /*27*/ {"//",null,null,null,null,"//",null,null,null},
       /*28*/ {"http://localhost/","http","//localhost","localhost",null,"/",null,null,null},
       /*29*/ {"http://localhost:8080/", "http", "//localhost:8080", "localhost","8080","/", null, null,null},
       /*30*/ {"http://localhost/?x=y", "http", "//localhost", "localhost",null,"/", null,"x=y",null},
       /*31*/ {"/;param",null, null, null,null,"/;param", "param",null,null},
       /*32*/ {"/?x=y",null, null, null,null,"/", null,"x=y",null},
       /*33*/ {"/?abc=test",null, null, null,null,"/", null,"abc=test",null},
       /*34*/ {"/#fragment",null, null, null,null,"/", null,null,"fragment"},
       /*35*/ {"http://192.0.0.1:8080/","http","//192.0.0.1:8080","192.0.0.1","8080","/",null,null,null},
       /*36*/ {"http://[2001:db8::1]:8080/","http","//[2001:db8::1]:8080","[2001:db8::1]","8080","/",null,null,null},
       /*37*/ {"http://user@[2001:db8::1]:8080/","http","//user@[2001:db8::1]:8080","[2001:db8::1]","8080","/",null,null,null},
       /*38*/ {"http://[2001:db8::1]/","http","//[2001:db8::1]","[2001:db8::1]",null,"/",null,null,null},
       /*39*/ {"//[2001:db8::1]:8080/",null,null,null,null,"//[2001:db8::1]:8080/",null,null,null},
       /*40*/ {"http://user@[2001:db8::1]:8080/","http","//user@[2001:db8::1]:8080","[2001:db8::1]","8080","/",null,null,null},
       /*41*/ {"*",null,null,null,null,"*",null, null,null}
    };

    @Test
    public void testPathURIs() throws Exception
    {
        HttpURI uri = new HttpURI();

        for (int t=0;t<path_tests.length;t++)
        {
            uri.parse(path_tests[t][0]);
            assertEquals(t+" "+path_tests[t][0],path_tests[t][1],uri.getScheme());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][2],uri.getAuthority());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][3],uri.getHost());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][4]==null?-1:Integer.parseInt(path_tests[t][4]),uri.getPort());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][5],uri.getPath());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][6],uri.getParam());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][7],uri.getQuery());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][8],uri.getFragment());
            assertEquals(path_tests[t][0], uri.toString());
        }

    }

    @Test
    public void testInvalidAddress() throws Exception
    {
        assertInvalidURI("http://[ffff::1:8080/", "Invalid URL; no closing ']' -- should throw exception");
        assertInvalidURI("**", "only '*', not '**'");
        assertInvalidURI("*/", "only '*', not '*/'");
    }

    private void assertInvalidURI(String invalidURI, String message)
    {
        HttpURI uri = new HttpURI();
        try
        {
            uri.parse(invalidURI);
            fail(message);
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(true);
        }
    }

    @Test
    public void testUnicodeErrors() throws UnsupportedEncodingException
    {
        String uri="http://server/path?invalid=data%uXXXXhere%u000";
        try
        {
            URLDecoder.decode(uri,"UTF-8");
            Assert.assertTrue(false);
        }
        catch (IllegalArgumentException e)
        {
        }

        HttpURI huri=new HttpURI(uri);
        MultiMap<String> params = new MultiMap<>();
        huri.decodeQueryTo(params);
        assertEquals("data"+Utf8Appendable.REPLACEMENT+"here"+Utf8Appendable.REPLACEMENT,params.getValue("invalid",0));

        huri=new HttpURI(uri);
        params = new MultiMap<>();
        huri.decodeQueryTo(params,StandardCharsets.UTF_8);
        assertEquals("data"+Utf8Appendable.REPLACEMENT+"here"+Utf8Appendable.REPLACEMENT,params.getValue("invalid",0));

    }

    @Test
    public void testExtB() throws Exception
    {
        for (String value: new String[]{"a","abcdABCD","\u00C0","\u697C","\uD869\uDED5","\uD840\uDC08"} )
        {
            HttpURI uri = new HttpURI("/path?value="+URLEncoder.encode(value,"UTF-8"));

            MultiMap<String> parameters = new MultiMap<>();
            uri.decodeQueryTo(parameters,StandardCharsets.UTF_8);
            assertEquals(value,parameters.getString("value"));
        }
    }


    private final String[][] connect_tests=
    {
       /* 0*/ {"localhost:8080","localhost","8080"},
       /* 1*/ {"127.0.0.1:8080","127.0.0.1","8080"},
       /* 2*/ {"[127::0::0::1]:8080","[127::0::0::1]","8080"},
       /* 3*/ {"error",null,null},
       /* 4*/ {"http://localhost:8080/",null,null},
    };

    @Test
    public void testCONNECT() throws Exception
    {
        HttpURI uri = new HttpURI();
        for (int i=0;i<connect_tests.length;i++)
        {
            try
            {
                uri.parseConnect(connect_tests[i][0]);
                assertEquals("path"+i,connect_tests[i][0].trim(),uri.getPath());
                assertEquals("host"+i,connect_tests[i][1],uri.getHost());
                assertEquals("port"+i,Integer.parseInt(connect_tests[i][2]),uri.getPort());
            }
            catch(Exception e)
            {
                assertNull("error"+i,connect_tests[i][1]);
            }
        }
    }
    

    @Test
    public void testParams() throws Exception
    {
        HttpURI uri = new HttpURI("/foo/bar");
        assertEquals("/foo/bar",uri.getPath());
        assertEquals("/foo/bar",uri.getDecodedPath());
        assertEquals(null,uri.getParam());
        
        uri = new HttpURI("/foo/bar;jsessionid=12345");
        assertEquals("/foo/bar;jsessionid=12345",uri.getPath());
        assertEquals("/foo/bar",uri.getDecodedPath());
        assertEquals("jsessionid=12345",uri.getParam());
        
        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345");
        assertEquals("/foo;abc=123/bar;jsessionid=12345",uri.getPath());
        assertEquals("/foo/bar",uri.getDecodedPath());
        assertEquals("jsessionid=12345",uri.getParam());
        
        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345?name=value");
        assertEquals("/foo;abc=123/bar;jsessionid=12345",uri.getPath());
        assertEquals("/foo/bar",uri.getDecodedPath());
        assertEquals("jsessionid=12345",uri.getParam());
        
        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345#target");
        assertEquals("/foo;abc=123/bar;jsessionid=12345",uri.getPath());
        assertEquals("/foo/bar",uri.getDecodedPath());
        assertEquals("jsessionid=12345",uri.getParam());
        
    }
}
