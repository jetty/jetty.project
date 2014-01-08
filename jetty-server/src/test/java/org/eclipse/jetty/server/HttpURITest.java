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

package org.eclipse.jetty.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8Appendable;
import org.junit.Assert;
import org.junit.Test;

public class HttpURITest
{
    private final String[][] partial_tests=
    {
       /* 0*/ {"/path/info",null,null,null,null,"/path/info",null,null,null},
       /* 1*/ {"/path/info#fragment",null,null,null,null,"/path/info",null,null,"fragment"},
       /* 2*/ {"/path/info?query",null,null,null,null,"/path/info",null,"query",null},
       /* 3*/ {"/path/info?query#fragment",null,null,null,null,"/path/info",null,"query","fragment"},
       /* 4*/ {"/path/info;param",null,null,null,null,"/path/info","param",null,null},
       /* 5*/ {"/path/info;param#fragment",null,null,null,null,"/path/info","param",null,"fragment"},
       /* 6*/ {"/path/info;param?query",null,null,null,null,"/path/info","param","query",null},
       /* 7*/ {"/path/info;param?query#fragment",null,null,null,null,"/path/info","param","query","fragment"},
       /* 8*/ {"//host/path/info",null,"//host","host",null,"/path/info",null,null,null},
       /* 9*/ {"//user@host/path/info",null,"//user@host","host",null,"/path/info",null,null,null},
       /*10*/ {"//user@host:8080/path/info",null,"//user@host:8080","host","8080","/path/info",null,null,null},
       /*11*/ {"//host:8080/path/info",null,"//host:8080","host","8080","/path/info",null,null,null},
       /*12*/ {"http:/path/info","http",null,null,null,"/path/info",null,null,null},
       /*13*/ {"http:/path/info#fragment","http",null,null,null,"/path/info",null,null,"fragment"},
       /*14*/ {"http:/path/info?query","http",null,null,null,"/path/info",null,"query",null},
       /*15*/ {"http:/path/info?query#fragment","http",null,null,null,"/path/info",null,"query","fragment"},
       /*16*/ {"http:/path/info;param","http",null,null,null,"/path/info","param",null,null},
       /*17*/ {"http:/path/info;param#fragment","http",null,null,null,"/path/info","param",null,"fragment"},
       /*18*/ {"http:/path/info;param?query","http",null,null,null,"/path/info","param","query",null},
       /*19*/ {"http:/path/info;param?query#fragment","http",null,null,null,"/path/info","param","query","fragment"},
       /*20*/ {"http://user@host:8080/path/info;param?query#fragment","http","//user@host:8080","host","8080","/path/info","param","query","fragment"},
       /*21*/ {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","//user@host:8080","host","8080","/path/info","param","query","fragment"},
       /*22*/ {"http:///;?#","http","//",null,null,"/","","",""},
       /*23*/ {"/path/info?a=?query",null,null,null,null,"/path/info",null,"a=?query",null},
       /*24*/ {"/path/info?a=;query",null,null,null,null,"/path/info",null,"a=;query",null},
       /*25*/ {"//host:8080//",null,"//host:8080","host","8080","//",null,null,null},
       /*26*/ {"file:///path/info","file","//",null,null,"/path/info",null,null,null},
       /*27*/ {"//",null,"//",null,null,null,null,null,null},
       /*28*/ {"/;param",null, null, null,null,"/", "param",null,null},
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
            uri.parse(partial_tests[t][0].getBytes(),0,partial_tests[t][0].length());
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
       /* 4*/ {"/path/info;param",null,null,null,null,"/path/info","param",null,null},
       /* 5*/ {"/path/info;param#fragment",null,null,null,null,"/path/info","param",null,"fragment"},
       /* 6*/ {"/path/info;param?query",null,null,null,null,"/path/info","param","query",null},
       /* 7*/ {"/path/info;param?query#fragment",null,null,null,null,"/path/info","param","query","fragment"},
       /* 8*/ {"//host/path/info",null,null,null,null,"//host/path/info",null,null,null},
       /* 9*/ {"//user@host/path/info",null,null,null,null,"//user@host/path/info",null,null,null},
       /*10*/ {"//user@host:8080/path/info",null,null,null,null,"//user@host:8080/path/info",null,null,null},
       /*11*/ {"//host:8080/path/info",null,null,null,null,"//host:8080/path/info",null,null,null},
       /*12*/ {"http:/path/info","http",null,null,null,"/path/info",null,null,null},
       /*13*/ {"http:/path/info#fragment","http",null,null,null,"/path/info",null,null,"fragment"},
       /*14*/ {"http:/path/info?query","http",null,null,null,"/path/info",null,"query",null},
       /*15*/ {"http:/path/info?query#fragment","http",null,null,null,"/path/info",null,"query","fragment"},
       /*16*/ {"http:/path/info;param","http",null,null,null,"/path/info","param",null,null},
       /*17*/ {"http:/path/info;param#fragment","http",null,null,null,"/path/info","param",null,"fragment"},
       /*18*/ {"http:/path/info;param?query","http",null,null,null,"/path/info","param","query",null},
       /*19*/ {"http:/path/info;param?query#fragment","http",null,null,null,"/path/info","param","query","fragment"},
       /*20*/ {"http://user@host:8080/path/info;param?query#fragment","http","//user@host:8080","host","8080","/path/info","param","query","fragment"},
       /*21*/ {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","//user@host:8080","host","8080","/path/info","param","query","fragment"},
       /*22*/ {"http:///;?#","http","//",null,null,"/","","",""},
       /*23*/ {"/path/info?a=?query",null,null,null,null,"/path/info",null,"a=?query",null},
       /*24*/ {"/path/info?a=;query",null,null,null,null,"/path/info",null,"a=;query",null},
       /*25*/ {"//host:8080//",null,null,null,null,"//host:8080//",null,null,null},
       /*26*/ {"file:///path/info","file","//",null,null,"/path/info",null,null,null},
       /*27*/ {"//",null,null,null,null,"//",null,null,null},
       /*28*/ {"http://localhost/","http","//localhost","localhost",null,"/",null,null,null},
       /*29*/ {"http://localhost:8080/", "http", "//localhost:8080", "localhost","8080","/", null, null,null},
       /*30*/ {"http://localhost/?x=y", "http", "//localhost", "localhost",null,"/", null,"x=y",null},
       /*31*/ {"/;param",null, null, null,null,"/", "param",null,null},
       /*32*/ {"/?x=y",null, null, null,null,"/", null,"x=y",null},
       /*33*/ {"/?abc=test",null, null, null,null,"/", null,"abc=test",null},
       /*34*/ {"/#fragment",null, null, null,null,"/", null,null,"fragment"},
       /*35*/ {"http://192.0.0.1:8080/","http","//192.0.0.1:8080","192.0.0.1","8080","/",null,null,null},
       /*36*/ {"http://[2001:db8::1]:8080/","http","//[2001:db8::1]:8080","2001:db8::1","8080","/",null,null,null},
       /*37*/ {"http://user@[2001:db8::1]:8080/","http","//user@[2001:db8::1]:8080","2001:db8::1","8080","/",null,null,null},
       /*38*/ {"http://[2001:db8::1]/","http","//[2001:db8::1]","2001:db8::1",null,"/",null,null,null},
       /*39*/ {"//[2001:db8::1]:8080/",null,null,null,null,"//[2001:db8::1]:8080/",null,null,null},
       /*40*/ {"http://user@[2001:db8::1]:8080/","http","//user@[2001:db8::1]:8080","2001:db8::1","8080","/",null,null,null},
       /*41*/ {"*",null,null,null,null,"*",null, null,null}
    };

    @Test
    public void testPathURIs() throws Exception
    {
        HttpURI uri = new HttpURI();

        for (int t=0;t<path_tests.length;t++)
        {
            uri.parse(path_tests[t][0].getBytes(),0,path_tests[t][0].length());
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

    private final String[][] encoding_tests=
    {
       /* 0*/ {"/path/info","/path/info", "UTF-8"},
       /* 1*/ {"/path/%69nfo","/path/info", "UTF-8"},
       /* 2*/ {"http://host/path/%69nfo","/path/info", "UTF-8"},
       /* 3*/ {"http://host/path/%69nf%c2%a4","/path/inf\u00a4", "UTF-8"},
       /* 4*/ {"http://host/path/%E5", "/path/\u00e5", "ISO-8859-1"},
       /* 5*/ {"/foo/%u30ED/bar%3Fabc%3D123%26xyz%3D456","/foo/\u30ed/bar?abc=123&xyz=456","UTF-8"}
    };

    @Test
    public void testEncoded()
    {
        HttpURI uri = new HttpURI();

        for (int t=0;t<encoding_tests.length;t++)
        {
            uri.parse(encoding_tests[t][0]);
            assertEquals(""+t,encoding_tests[t][1],uri.getDecodedPath(encoding_tests[t][2]));
            
            if ("UTF-8".equalsIgnoreCase(encoding_tests[t][2]))
                assertEquals(""+t,encoding_tests[t][1],uri.getDecodedPath());
        }
    }
    
    @Test
    public void testNoPercentEncodingOfQueryUsingNonUTF8() throws Exception
    {
        byte[] utf8_bytes = "/%D0%A1%D1%82%D1%80%D0%BE%D0%BD%D0%B3-%D1%84%D0%B8%D0%BB%D1%8C%D1%82%D1%80/%D0%BA%D0%B0%D1%82%D0%B0%D0%BB%D0%BE%D0%B3?".getBytes("UTF-8");
        byte[] cp1251_bytes = TypeUtil.fromHexString("e2fbe1f0e0edee3dd2e5ecefe5f0e0f2f3f0e0");
        String expectedCP1251String = new String(cp1251_bytes, "cp1251");
        String expectedCP1251Key = new String(cp1251_bytes, 0, 7, "cp1251");
        String expectedCP1251Value = new String(cp1251_bytes, 8, cp1251_bytes.length-8, "cp1251");
       
        //paste both byte arrays together to form the uri
        byte[] allbytes = new byte[utf8_bytes.length+cp1251_bytes.length];
        int i=0;
        for (;i<utf8_bytes.length;i++) {
            allbytes[i] = utf8_bytes[i];
        }
        for (int j=0; j< cp1251_bytes.length;j++)
            allbytes[i+j] = cp1251_bytes[j];
        
        //Test using a HttpUri that expects a particular charset encoding. See URIUtil.__CHARSET
        HttpURI uri = new HttpURI(Charset.forName("cp1251"));
        uri.parse(allbytes, 0, allbytes.length);
        assertEquals(expectedCP1251String, uri.getQuery("cp1251"));
        
        //Test params decoded correctly
        MultiMap params = new MultiMap();
        uri.decodeQueryTo(params);
        String val = params.getString(expectedCP1251Key);
        assertNotNull(val);
        assertEquals(expectedCP1251Value, val);
        
        //Test using HttpURI where you pass in the charset encoding.
        HttpURI httpuri = new HttpURI();
        httpuri.parse(allbytes,0,allbytes.length);
        assertNotNull(httpuri.getQuery("UTF-8")); //still get back a query string, just incorrectly encoded
        assertEquals(expectedCP1251String, httpuri.getQuery("cp1251"));
        
        //Test params decoded correctly
        params.clear();
        httpuri.decodeQueryTo(params, "cp1251");
        val = params.getString(expectedCP1251Key);
        assertNotNull(val);
        assertEquals(expectedCP1251Value, val);
        
        //test able to set the query encoding and call getQueryString multiple times
        Request request = new Request(null,null);
        request.setUri(httpuri);    
        request.setAttribute("org.eclipse.jetty.server.Request.queryEncoding", "ISO-8859-1");
        assertNotNull (request.getQueryString()); //will be incorrect encoding but not null
        request.setAttribute("org.eclipse.jetty.server.Request.queryEncoding", "cp1251");
        assertEquals(expectedCP1251String, request.getQueryString());
    }
    
    @Test
    public void testPercentEncodingOfQueryStringUsingNonUTF8() throws UnsupportedEncodingException
    {
    
      byte[] utf8_bytes = "/%D0%A1%D1%82%D1%80%D0%BE%D0%BD%D0%B3-%D1%84%D0%B8%D0%BB%D1%8C%D1%82%D1%80/%D0%BA%D0%B0%D1%82%D0%B0%D0%BB%D0%BE%D0%B3?".getBytes("UTF-8");
      byte[] cp1251_bytes = "%e2%fb%e1%f0%e0%ed%ee=%d2%e5%ec%ef%e5%f0%e0%f2%f3%f0%e0".getBytes("cp1251");
      
      byte[] key_bytes = TypeUtil.fromHexString("e2fbe1f0e0edee");
      byte[] val_bytes = TypeUtil.fromHexString("d2e5ecefe5f0e0f2f3f0e0");
      String expectedCP1251String = new String(cp1251_bytes, "cp1251");
      String expectedCP1251Key = new String(key_bytes, "cp1251");
      String expectedCP1251Value = new String(val_bytes, "cp1251");
      
      byte[] allbytes = new byte[utf8_bytes.length+cp1251_bytes.length];
      
      //stick both arrays together to form uri
      int i=0;
      for (;i<utf8_bytes.length;i++) {
          allbytes[i] = utf8_bytes[i];
      }
      for (int j=0; j< cp1251_bytes.length;j++)
          allbytes[i+j] = cp1251_bytes[j];


      HttpURI httpuri = new HttpURI();
      httpuri.parse(allbytes,0,allbytes.length);
      assertNotNull(httpuri.getQuery("UTF-8")); //will be incorrectly encoded, but no errors
      assertEquals(expectedCP1251String, httpuri.getQuery("cp1251"));

      //test params decoded correctly
      MultiMap params = new MultiMap();
      httpuri.decodeQueryTo(params, "cp1251");
      String val = params.getString(expectedCP1251Key);
      assertNotNull(val);
      assertEquals(expectedCP1251Value, val);
      
      //test able to set the query encoding and call getQueryString multiple times
      Request request = new Request(null,null);
      request.setUri(httpuri);    
      request.setAttribute("org.eclipse.jetty.server.Request.queryEncoding", "ISO-8859-1");
      assertNotNull (request.getQueryString()); //will be incorrect encoding but not null
      request.setAttribute("org.eclipse.jetty.server.Request.queryEncoding", "cp1251");
      assertEquals(expectedCP1251String, request.getQueryString());
      
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
       /* 0*/ {"  localhost:8080  ","localhost","8080"},
       /* 1*/ {"  127.0.0.1:8080  ","127.0.0.1","8080"},
       /* 2*/ {"  [127::0::0::1]:8080  ","127::0::0::1","8080"},
       /* 3*/ {"  error  ",null,null},
       /* 4*/ {"  http://localhost:8080/  ",null,null},
    };

    @Test
    public void testCONNECT() throws Exception
    {
        HttpURI uri = new HttpURI();
        for (int i=0;i<connect_tests.length;i++)
        {
            try
            {
                byte[] buf = connect_tests[i][0].getBytes(StandardCharsets.UTF_8);

                uri.parseConnect(buf,2,buf.length-4);
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
    public void testNonURIAscii() throws Exception
    {
        String url = "http://www.foo.com/ma\u00F1ana";
        byte[] asISO = url.getBytes(StandardCharsets.ISO_8859_1);
        new String(asISO, StandardCharsets.ISO_8859_1);

        //use a non UTF-8 charset as the encoding and url-escape as per
        //http://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars
        String s = URLEncoder.encode(url, "ISO-8859-1");
        HttpURI uri = new HttpURI(StandardCharsets.ISO_8859_1);

        //parse it, using the same encoding
        uri.parse(s);

        //decode the url encoding
        String d = URLDecoder.decode(uri.getCompletePath(), "ISO-8859-1");
        assertEquals(url, d);
    }
}
