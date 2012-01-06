// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class RequestTest
{
    private Server _server;
    private LocalConnector _connector;
    private RequestHandler _handler;

    @Before
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector();
        _connector.setRequestHeaderSize(512);
        _connector.setRequestBufferSize(1024);
        _connector.setResponseHeaderSize(512);
        _connector.setResponseBufferSize(2048);
        _connector.setForwarded(true);
        _server.addConnector(_connector);
        _handler = new RequestHandler();
        _server.setHandler(_handler);
        _server.start();
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testParamExtraction() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                Map map = null;
                try
                {
                    //do the parse
                    request.getParameterMap();
                    Assert.fail("Expected parsing failure");
                    return false;
                }
                catch (Exception e)
                {
                    //catch the error and check the param map is not null
                    map = request.getParameterMap();
                    System.err.println(map);
                    assertFalse(map == null);
                    assertTrue(map.isEmpty());

                    Enumeration names = request.getParameterNames();
                    assertFalse(names.hasMoreElements());
                }

                return true;
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request="GET /?param=%ZZaaa HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: text/html;charset=utf8\n"+
        "\n";

        String responses=_connector.getResponses(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));

    }

    @Test
    public void testBadUtf8ParamExtraction() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                String value=request.getParameter("param");
                return value.startsWith("aaa") && value.endsWith("bb");
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request="GET /?param=aaa%E7bbb HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: text/html;charset=utf8\n"+
        "\n";

        String responses=_connector.getResponses(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testInvalidHostHeader() throws Exception
    {
        // Use a contextHandler with vhosts to force call to Request.getServerName()
        ContextHandler handler = new ContextHandler();
        handler.addVirtualHosts(new String[1]);
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        // Request with illegal Host header
        String request="GET / HTTP/1.1\r\n"+
        "Host: whatever.com:\r\n"+
        "Content-Type: text/html;charset=utf8\n"+
        "\n";

        String responses=_connector.getResponses(request);
        assertTrue("400 Bad Request response expected",responses.startsWith("HTTP/1.1 400"));
    }



    @Test
    public void testContentTypeEncoding() throws Exception
    {
        final ArrayList<String> results = new ArrayList<String>();
        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                results.add(request.getContentType());
                results.add(request.getCharacterEncoding());
                return true;
            }
        };

        _connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: whatever\n"+
                "Content-Type: text/test\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: whatever\n"+
                "Content-Type: text/html;charset=utf8\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: whatever\n"+
                "Content-Type: text/html; charset=\"utf8\"\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: whatever\n"+
                "Content-Type: text/html; other=foo ; blah=\"charset=wrong;\" ; charset =   \" x=z; \"   ; more=values \n"+
                "\n"
                );

        int i=0;
        assertEquals("text/test",results.get(i++));
        assertEquals(null,results.get(i++));

        assertEquals("text/html;charset=utf8",results.get(i++));
        assertEquals("utf8",results.get(i++));

        assertEquals("text/html; charset=\"utf8\"",results.get(i++));
        assertEquals("utf8",results.get(i++));

        assertTrue(results.get(i++).startsWith("text/html"));
        assertEquals(" x=z; ",results.get(i++));
    }

    @Test
    public void testHostPort() throws Exception
    {
        final ArrayList<String> results = new ArrayList<String>();
        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                results.add(request.getRemoteAddr());
                results.add(request.getServerName());
                results.add(String.valueOf(request.getServerPort()));
                return true;
            }
        };

        _connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: myhost\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: myhost:8888\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: 1.2.3.4\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: 1.2.3.4:8888\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: [::1]\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: [::1]:8888\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: [::1]\n"+
                "x-forwarded-for: remote\n"+
                "x-forwarded-proto: https\n"+
                "\n"+

                "GET / HTTP/1.1\n"+
                "Host: [::1]:8888\n"+
                "x-forwarded-for: remote\n"+
                "x-forwarded-proto: https\n"+
                "\n"
                );

        int i=0;
        assertEquals(null,results.get(i++));
        assertEquals("myhost",results.get(i++));
        assertEquals("80",results.get(i++));
        assertEquals(null,results.get(i++));
        assertEquals("myhost",results.get(i++));
        assertEquals("8888",results.get(i++));
        assertEquals(null,results.get(i++));
        assertEquals("1.2.3.4",results.get(i++));
        assertEquals("80",results.get(i++));
        assertEquals(null,results.get(i++));
        assertEquals("1.2.3.4",results.get(i++));
        assertEquals("8888",results.get(i++));
        assertEquals(null,results.get(i++));
        assertEquals("[::1]",results.get(i++));
        assertEquals("80",results.get(i++));
        assertEquals(null,results.get(i++));
        assertEquals("[::1]",results.get(i++));
        assertEquals("8888",results.get(i++));
        assertEquals("remote",results.get(i++));
        assertEquals("[::1]",results.get(i++));
        assertEquals("443",results.get(i++));
        assertEquals("remote",results.get(i++));
        assertEquals("[::1]",results.get(i++));
        assertEquals("8888",results.get(i++));

    }

    @Test
    public void testContent() throws Exception
    {
        final int[] length=new int[1];

        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                assertEquals(request.getContentLength(), ((Request)request).getContentRead());
                length[0]=request.getContentLength();
                return true;
            }
        };

        String content="";

        for (int l=0;l<1025;l++)
        {
            String request="POST / HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "Content-Type: text/test\r\n"+
            "Content-Length: "+l+"\r\n"+
            "Connection: close\r\n"+
            "\r\n"+
            content;
            String response = _connector.getResponses(request);
            assertEquals(l,length[0]);
            if (l>0)
                assertEquals(l,_handler._content.length());
            content+="x";
        }
    }

    @Test
    public void testPartialRead() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                    ServletException
            {
                baseRequest.setHandled(true);
                Reader reader=request.getReader();
                byte[] b=("read="+reader.read()+"\n").getBytes(StringUtil.__UTF8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }

        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String request="GET / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: text/plane\r\n"+
        "Content-Length: "+10+"\r\n"+
        "\r\n"+
        "0123456789\r\n"+
        "GET / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: text/plane\r\n"+
        "Content-Length: "+10+"\r\n"+
        "Connection: close\r\n"+
        "\r\n"+
        "ABCDEFGHIJ\r\n";

        String responses = _connector.getResponses(request);

        int index=responses.indexOf("read="+(int)'0');
        assertTrue(index>0);

        index=responses.indexOf("read="+(int)'A',index+7);
        assertTrue(index>0);
    }

    @Test
    public void testQueryAfterRead()
        throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException
            {
                baseRequest.setHandled(true);
                Reader reader=request.getReader();
                String in = IO.toString(reader);
                String param = request.getParameter("param");

                byte[] b=("read='"+in+"' param="+param+"\n").getBytes(StringUtil.__UTF8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String request="POST /?param=right HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: application/x-www-form-urlencoded\r\n"+
        "Content-Length: "+11+"\r\n"+
        "Connection: close\r\n"+
        "\r\n"+
        "param=wrong\r\n";

        String responses = _connector.getResponses(request);

        assertTrue(responses.indexOf("read='param=wrong' param=right")>0);

    }

    @Test
    public void testPartialInput() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException
            {
                baseRequest.setHandled(true);
                InputStream in=request.getInputStream();
                byte[] b=("read="+in.read()+"\n").getBytes(StringUtil.__UTF8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }

        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String request="GET / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: text/plane\r\n"+
        "Content-Length: "+10+"\r\n"+
        "\r\n"+
        "0123456789\r\n"+
        "GET / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: text/plane\r\n"+
        "Content-Length: "+10+"\r\n"+
        "Connection: close\r\n"+
        "\r\n"+
        "ABCDEFGHIJ\r\n";

        String responses = _connector.getResponses(request);

        int index=responses.indexOf("read="+(int)'0');
        assertTrue(index>0);

        index=responses.indexOf("read="+(int)'A',index+7);
        assertTrue(index>0);
    }

    @Test
    public void testConnectionClose() throws Exception
    {
        String response;

        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "\n"
                    );
        assertTrue(response.indexOf("200")>0);
        assertFalse(response.indexOf("Connection: close")>0);
        assertTrue(response.indexOf("Hello World")>0);

        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "Connection: close\n"+
                    "\n"
                    );
        assertTrue(response.indexOf("200")>0);
        assertTrue(response.indexOf("Connection: close")>0);
        assertTrue(response.indexOf("Hello World")>0);

        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "Connection: Other, close\n"+
                    "\n"
                    );

        assertTrue(response.indexOf("200")>0);
        assertTrue(response.indexOf("Connection: close")>0);
        assertTrue(response.indexOf("Hello World")>0);

        response=_connector.getResponses(
                    "GET / HTTP/1.0\n"+
                    "Host: whatever\n"+
                    "\n"
                    );
        assertTrue(response.indexOf("200")>0);
        assertFalse(response.indexOf("Connection: close")>0);
        assertTrue(response.indexOf("Hello World")>0);

        response=_connector.getResponses(
                    "GET / HTTP/1.0\n"+
                    "Host: whatever\n"+
                    "Connection: Other, close\n"+
                    "\n"
                    );
        assertTrue(response.indexOf("200")>0);
        assertTrue(response.indexOf("Hello World")>0);

        response=_connector.getResponses(
                    "GET / HTTP/1.0\n"+
                    "Host: whatever\n"+
                    "Connection: Other,,keep-alive\n"+
                    "\n"
                    );
        assertTrue(response.indexOf("200")>0);
        assertTrue(response.indexOf("Connection: keep-alive")>0);
        assertTrue(response.indexOf("Hello World")>0);

        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                response.setHeader("Connection","TE");
                response.addHeader("Connection","Other");
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "\n"
                    );
        assertTrue(response.indexOf("200")>0);
        assertTrue(response.indexOf("Connection: TE,Other")>0);
        assertTrue(response.indexOf("Hello World")>0);

        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "Connection: close\n"+
                    "\n"
                    );
        assertTrue(response.indexOf("200")>0);
        assertTrue(response.indexOf("Connection: close")>0);
        assertTrue(response.indexOf("Hello World")>0);
    }

    @Test
    public void testCookies() throws Exception
    {
        final ArrayList<Cookie> cookies = new ArrayList<Cookie>();

        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                javax.servlet.http.Cookie[] ca = request.getCookies();
                if (ca!=null)
                    cookies.addAll(Arrays.asList(ca));
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        String response;

        cookies.clear();
        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "\n"
                    );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(0,cookies.size());


        cookies.clear();
        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "Cookie: name=quoted=\\\"value\\\"\n" +
                    "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1,cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("quoted=\\\"value\\\"", cookies.get(0).getValue());

        cookies.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: whatever\n"+
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(2,cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());


        cookies.clear();
        response=_connector.getResponses(
                "GET /other HTTP/1.1\n"+
                "Host: whatever\n"+
                "Other: header\n"+
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n"+
                "GET /other HTTP/1.1\n"+
                "Host: whatever\n"+
                "Other: header\n"+
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(4,cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        assertSame(cookies.get(0), cookies.get(2));
        assertSame(cookies.get(1), cookies.get(3));

        cookies.clear();
        response=_connector.getResponses(
                "GET /other HTTP/1.1\n"+
                "Host: whatever\n"+
                "Other: header\n"+
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n"+
                "GET /other HTTP/1.1\n"+
                "Host: whatever\n"+
                "Other: header\n"+
                "Cookie: name=value; other=\"othervalue\"\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(4,cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        assertNotSame(cookies.get(0), cookies.get(2));
        assertNotSame(cookies.get(1), cookies.get(3));

        cookies.clear();
        response=_connector.getResponses(
                "POST / HTTP/1.1\r\n"+
                "Host: whatever\r\n"+
                "Cookie: name0=value0; name1 = value1 ; \"\\\"name2\\\"\"  =  \"\\\"value2\\\"\"  \n" +
                "Cookie: $Version=2; name3=value3=value3;$path=/path;$domain=acme.com;$port=8080, name4=; name5 =  ; name6\n" +
                "Cookie: name7=value7;\n" +
                "Connection: close\r\n"+
        "\r\n");

        assertEquals("name0", cookies.get(0).getName());
        assertEquals("value0", cookies.get(0).getValue());
        assertEquals("name1", cookies.get(1).getName());
        assertEquals("value1", cookies.get(1).getValue());
        assertEquals("\"name2\"", cookies.get(2).getName());
        assertEquals("\"value2\"", cookies.get(2).getValue());
        assertEquals("name3", cookies.get(3).getName());
        assertEquals("value3=value3", cookies.get(3).getValue());
        assertEquals(2, cookies.get(3).getVersion());
        assertEquals("/path", cookies.get(3).getPath());
        assertEquals("acme.com", cookies.get(3).getDomain());
        assertEquals("$port=8080", cookies.get(3).getComment());
        assertEquals("name4", cookies.get(4).getName());
        assertEquals("", cookies.get(4).getValue());
        assertEquals("name5", cookies.get(5).getName());
        assertEquals("", cookies.get(5).getValue());
        assertEquals("name6", cookies.get(6).getName());
        assertEquals("", cookies.get(6).getValue());
        assertEquals("name7", cookies.get(7).getName());
        assertEquals("value7", cookies.get(7).getValue());
    }

    @Test
    public void testCookieLeak() throws Exception
    {
        final String[] cookie=new String[10];

        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                for (int i=0;i<cookie.length; i++)
                    cookie[i]=null;

                Cookie[] cookies = request.getCookies();
                for (int i=0;cookies!=null && i<cookies.length; i++)
                {
                    cookie[i]=cookies[i].getValue();
                }
                return true;
            }
        };

        String request="POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Cookie: other=cookie\r\n"+
        "\r\n"
        +
        "POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Cookie: name=value\r\n"+
        "Connection: close\r\n"+
        "\r\n";

        _connector.getResponses(request);

        assertEquals("value",cookie[0]);
        assertEquals(null,cookie[1]);

        request="POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Cookie: name=value\r\n"+
        "\r\n"
        +
        "POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Cookie:\r\n"+
        "Connection: close\r\n"+
        "\r\n";

        _connector.getResponses(request);
        assertEquals(null,cookie[0]);
        assertEquals(null,cookie[1]);

        request="POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Cookie: name=value\r\n"+
        "Cookie: other=cookie\r\n"+
        "\r\n"
        +
        "POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Cookie: name=value\r\n"+
        "Cookie:\r\n"+
        "Connection: close\r\n"+
        "\r\n";

        _connector.getResponses(request);

        assertEquals("value",cookie[0]);
        assertEquals(null,cookie[1]);
    }


    @Test
    public void testHashDOS() throws Exception
    {
        _server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize",-1);
        _server.setAttribute("org.eclipse.jetty.server.Request.maxFormKeys",1000);

        // This file is not distributed - as it is dangerous
        File evil_keys = new File("/tmp/keys_mapping_to_zero_2m");
        if (!evil_keys.exists())
        {
            Log.info("testHashDOS skipped");
            return;
        }

        BufferedReader in = new BufferedReader(new FileReader(evil_keys));
        StringBuilder buf = new StringBuilder(4000000);

        String key=null;
        buf.append("a=b");
        while((key=in.readLine())!=null)
        {
            buf.append("&").append(key).append("=").append("x");
        }
        buf.append("&c=d");

        _handler._checker = new RequestTester()
        {
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                return "b".equals(request.getParameter("a")) && request.getParameter("c")==null;
            }
        };

        String request="POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: "+MimeTypes.FORM_ENCODED+"\r\n"+
        "Content-Length: "+buf.length()+"\r\n"+
        "Connection: close\r\n"+
        "\r\n"+
        buf;

        long start=System.currentTimeMillis();
        String response = _connector.getResponses(request);
        assertTrue(response.contains("200 OK"));
        long now=System.currentTimeMillis();
        assertTrue((now-start)<5000);
    }


    interface RequestTester
    {
        boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException;
    }

    private class RequestHandler extends AbstractHandler
    {
        private RequestTester _checker;
        private String _content;

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);

            if (request.getContentLength()>0 && !MimeTypes.FORM_ENCODED.equals(request.getContentType()))
                _content=IO.toString(request.getInputStream());

            if (_checker!=null && _checker.check(request,response))
                response.setStatus(200);
            else
                response.sendError(500);


        }
    }
}
