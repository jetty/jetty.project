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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.HashedSession;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ResponseTest
{
    private Server _server;
    private HttpChannel<ByteBuffer> _channel;

    @Before
    public void init() throws Exception
    {
        _server = new Server();
        Scheduler _scheduler = new TimerScheduler();
        HttpConfiguration config = new HttpConfiguration();
        LocalConnector connector = new LocalConnector(_server,null, _scheduler,null,1,new HttpConnectionFactory(config));
        _server.addConnector(connector);
        _server.setHandler(new DumpHandler());
        _server.start();

        AbstractEndPoint endp = new ByteArrayEndPoint(_scheduler, 5000);
        ByteBufferQueuedHttpInput input = new ByteBufferQueuedHttpInput();
        _channel = new HttpChannel<ByteBuffer>(connector, new HttpConfiguration(), endp, new HttpTransport()
        {
            @Override
            public void send(ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
            {
                callback.succeeded();
            }
            
            @Override
            public void send(ByteBuffer responseBodyContent, boolean lastContent, Callback callback)
            {
                send(null,responseBodyContent, lastContent, callback);
            }

            @Override
            public void completed()
            {
            }

            @Override
            public void abort()
            {
                
            }

        }, input);
    }

    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testContentType() throws Exception
    {
        Response response = newResponse();

        assertEquals(null, response.getContentType());

        response.setHeader("Content-Type", "text/something");
        assertEquals("text/something", response.getContentType());

        response.setContentType("foo/bar");
        assertEquals("foo/bar", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=ISO-8859-1", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2; charset=ISO-8859-1", response.getContentType());
        response.setHeader("name", "foo");

        Iterator<String> en = response.getHeaders("name").iterator();
        assertEquals("foo", en.next());
        assertFalse(en.hasNext());
        response.addHeader("name", "bar");
        en = response.getHeaders("name").iterator();
        assertEquals("foo", en.next());
        assertEquals("bar", en.next());
        assertFalse(en.hasNext());

        response.recycle();

        response.setContentType("text/html");
        assertEquals("text/html", response.getContentType());
        response.getWriter();
        assertEquals("text/html; charset=ISO-8859-1", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2; charset=ISO-8859-1", response.getContentType());

        response.recycle();
        response.setContentType("text/xml;charset=ISO-8859-7");
        response.getWriter();
        assertEquals("text/xml;charset=ISO-8859-7", response.getContentType());
        response.setContentType("text/html;charset=UTF-8");
        assertEquals("text/html; charset=ISO-8859-7", response.getContentType());

        response.recycle();
        response.setContentType("text/html;charset=US-ASCII");
        response.getWriter();
        assertEquals("text/html;charset=US-ASCII", response.getContentType());

        response.recycle();
        response.setContentType("text/html; charset=utf-8");
        response.getWriter();
        assertEquals("text/html; charset=UTF-8", response.getContentType());

        response.recycle();
        response.setContentType("text/json");
        response.getWriter();
        assertEquals("text/json", response.getContentType());
        
        response.recycle();
        response.setContentType("text/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter();
        assertEquals("text/json; charset=UTF-8", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar");
        assertEquals("foo/bar; charset=xyz", response.getContentType());

        response.recycle();
        response.setContentType("foo/bar");
        response.setCharacterEncoding("xyz");
        assertEquals("foo/bar; charset=xyz", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar;charset=abc");
        assertEquals("foo/bar;charset=abc", response.getContentType());

        response.recycle();
        response.setContentType("foo/bar;charset=abc");
        response.setCharacterEncoding("xyz");
        assertEquals("foo/bar; charset=xyz", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar");
        response.setCharacterEncoding(null);
        assertEquals("foo/bar", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setCharacterEncoding(null);
        response.setContentType("foo/bar");
        assertEquals("foo/bar", response.getContentType());
        response.recycle();
        response.addHeader("Content-Type","text/something");
        assertEquals("text/something",response.getContentType());
        
        response.recycle();
        response.addHeader("Content-Type","application/json");
        response.getWriter();
        assertEquals("application/json",response.getContentType());

    }

    @Test
    public void testLocale() throws Exception
    {
        Response response = newResponse();

        ContextHandler context = new ContextHandler();
        context.addLocaleEncoding(Locale.ENGLISH.toString(), "ISO-8859-1");
        context.addLocaleEncoding(Locale.ITALIAN.toString(), "ISO-8859-2");
        response.getHttpChannel().getRequest().setContext(context.getServletContext());

        response.setLocale(java.util.Locale.ITALIAN);
        assertEquals(null, response.getContentType());
        response.setContentType("text/plain");
        assertEquals("text/plain; charset=ISO-8859-2", response.getContentType());

        response.recycle();
        response.setContentType("text/plain");
        response.setCharacterEncoding("utf-8");
        response.setLocale(java.util.Locale.ITALIAN);
        assertEquals("text/plain; charset=UTF-8", response.getContentType());
        assertTrue(response.toString().indexOf("charset=UTF-8") > 0);
    }

    @Test
    public void testContentTypeCharacterEncoding() throws Exception
    {
        Response response = newResponse();

        response.setContentType("foo/bar");
        response.setCharacterEncoding("utf-8");
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2; charset=UTF-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2; charset=UTF-8", response.getContentType());

        response.recycle();

        response.setContentType("text/html");
        response.setCharacterEncoding("utf-8");
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml; charset=UTF-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("text/xml; charset=UTF-8", response.getContentType());
    }

    @Test
    public void testCharacterEncodingContentType() throws Exception
    {
        Response response = newResponse();
        response.setCharacterEncoding("utf-8");
        response.setContentType("foo/bar");
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2; charset=UTF-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2; charset=UTF-8", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html");
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml; charset=UTF-8", response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml; charset=UTF-8", response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncoding() throws Exception
    {
        Response response = newResponse();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8");
        assertEquals("foo/bar; charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2; charset=UTF-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2; charset=UTF-8", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; charset=utf-8");
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml; charset=UTF-8", response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml; charset=UTF-8", response.getContentType());
    }

    @Test
    public void testContentTypeWithOther() throws Exception
    {
        Response response = newResponse();

        response.setContentType("foo/bar; other=xyz");
        assertEquals("foo/bar; other=xyz", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=xyz; charset=ISO-8859-1", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2; charset=ISO-8859-1", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html; other=xyz");
        assertEquals("text/html; other=xyz; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz; charset=UTF-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml; charset=UTF-8", response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncodingAndOther() throws Exception
    {
        Response response = newResponse();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8 other=xyz");
        assertEquals("foo/bar; charset=utf-8 other=xyz", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8 other=xyz", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; other=xyz charset=utf-8");
        assertEquals("text/html; other=xyz charset=utf-8; charset=UTF-16", response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz charset=utf-8; charset=UTF-16", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; other=pq charset=utf-8 other=xyz");
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz; charset=UTF-16", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz; charset=UTF-16", response.getContentType());
    }

    @Test
    public void testStatusCodes() throws Exception
    {
        Response response = newResponse();

        response.sendError(404);
        assertEquals(404, response.getStatus());
        assertEquals(null, response.getReason());

        response = newResponse();

        response.sendError(500, "Database Error");
        assertEquals(500, response.getStatus());
        assertEquals("Database Error", response.getReason());
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeader.CACHE_CONTROL.asString()));

        response = newResponse();

        response.setStatus(200);
        assertEquals(200, response.getStatus());
        assertEquals(null, response.getReason());

        response = newResponse();

        response.sendError(406, "Super Nanny");
        assertEquals(406, response.getStatus());
        assertEquals("Super Nanny", response.getReason());
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeader.CACHE_CONTROL.asString()));
    }

    @Test
    public void testEncodeRedirect()
            throws Exception
    {
        Response response = newResponse();
        Request request = response.getHttpChannel().getRequest();
        request.setServerName("myhost");
        request.setServerPort(8888);
        request.setContextPath("/path");

        assertEquals("http://myhost:8888/path/info;param?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));

        request.setRequestedSessionId("12345");
        request.setRequestedSessionIdFromCookie(false);
        HashSessionManager manager = new HashSessionManager();
        manager.setSessionIdManager(new HashSessionIdManager());
        request.setSessionManager(manager);
        request.setSession(new TestSession(manager, "12345"));

        manager.setCheckingRemoteSessionIdEncoding(false);

        assertEquals("http://myhost:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost:8888/other/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/other/info;param?query=0&more=1#target"));

        manager.setCheckingRemoteSessionIdEncoding(true);
        assertEquals("http://myhost:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param?query=0&more=1#target", response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost/path/info;param?query=0&more=1#target", response.encodeURL("http://myhost/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost:8888/other/info;param?query=0&more=1#target", response.encodeURL("http://myhost:8888/other/info;param?query=0&more=1#target"));

        request.setContextPath("");
        assertEquals("http://myhost:8888/;jsessionid=12345", response.encodeURL("http://myhost:8888"));
        assertEquals("https://myhost:8888/;jsessionid=12345", response.encodeURL("https://myhost:8888"));
        assertEquals("mailto:/foo", response.encodeURL("mailto:/foo"));
        assertEquals("http://myhost:8888/;jsessionid=12345", response.encodeURL("http://myhost:8888/"));
        assertEquals("http://myhost:8888/;jsessionid=12345", response.encodeURL("http://myhost:8888/;jsessionid=7777"));
        assertEquals("http://myhost:8888/;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param?query=0&more=1#target", response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        manager.setCheckingRemoteSessionIdEncoding(false);
        assertEquals("/foo;jsessionid=12345", response.encodeURL("/foo"));
        assertEquals("/;jsessionid=12345", response.encodeURL("/"));
        assertEquals("/foo.html;jsessionid=12345#target", response.encodeURL("/foo.html#target"));
        assertEquals(";jsessionid=12345", response.encodeURL(""));
    }

    @Test
    public void testSendRedirect()
            throws Exception
    {
        String[][] tests = {
                // No cookie
                {"http://myhost:8888/other/location;jsessionid=12345?name=value","http://myhost:8888/other/location;jsessionid=12345?name=value"},
                {"/other/location;jsessionid=12345?name=value","http://@HOST@@PORT@/other/location;jsessionid=12345?name=value"},
                {"./location;jsessionid=12345?name=value","http://@HOST@@PORT@/path/location;jsessionid=12345?name=value"},

                // From cookie
                {"/other/location","http://@HOST@@PORT@/other/location"},
                {"/other/l%20cation", "http://@HOST@@PORT@/other/l%20cation"},
                {"location", "http://@HOST@@PORT@/path/location"},
                {"./location", "http://@HOST@@PORT@/path/location"},
                {"../location", "http://@HOST@@PORT@/location"},
                {"/other/l%20cation", "http://@HOST@@PORT@/other/l%20cation"},
                {"l%20cation", "http://@HOST@@PORT@/path/l%20cation"},
                {"./l%20cation", "http://@HOST@@PORT@/path/l%20cation"},
                {"../l%20cation","http://@HOST@@PORT@/l%20cation"},
                {"../locati%C3%abn", "http://@HOST@@PORT@/locati%C3%ABn"},
                {"http://somehost.com/other/location","http://somehost.com/other/location"},
        };

        int[] ports=new int[]{8080,80};
        String[] hosts=new String[]{"myhost","192.168.0.1","0::1"};
        for (int port : ports)
        {
            for (String host : hosts)
            {
                for (int i=0;i<tests.length;i++)
                {
                    Response response = newResponse();
                    Request request = response.getHttpChannel().getRequest();

                    request.setServerName(host);
                    request.setServerPort(port);
                    request.setUri(new HttpURI("/path/info;param;jsessionid=12345?query=0&more=1#target"));
                    request.setContextPath("/path");
                    request.setRequestedSessionId("12345");
                    request.setRequestedSessionIdFromCookie(i>2);
                    HashSessionManager manager = new HashSessionManager();
                    manager.setSessionIdManager(new HashSessionIdManager());
                    request.setSessionManager(manager);
                    request.setSession(new TestSession(manager, "12345"));
                    manager.setCheckingRemoteSessionIdEncoding(false);

                    response.sendRedirect(tests[i][0]);

                    String location = response.getHeader("Location");

                    String expected=tests[i][1].replace("@HOST@",host.contains(":")?("["+host+"]"):host).replace("@PORT@",port==80?"":(":"+port));
                    assertEquals("test-"+i+" "+host+":"+port,expected,location);
                }
            }
        }
    }

    @Test
    public void testSetBufferSizeAfterHavingWrittenContent() throws Exception
    {
        Response response = newResponse();
        response.setBufferSize(20 * 1024);
        response.getWriter().print("hello");
        try
        {
            response.setBufferSize(21 * 1024);
            fail("Expected IllegalStateException on Request.setBufferSize");
        }
        catch (Exception e)
        {
            assertTrue(e instanceof IllegalStateException);
        }
    }

    @Test
    public void testZeroContent() throws Exception
    {
        Response response = newResponse();
        PrintWriter writer = response.getWriter();
        response.setContentLength(0);
        assertTrue(!response.isCommitted());
        assertTrue(!writer.checkError());
        writer.print("");
        assertTrue(!writer.checkError());
        assertTrue(response.isCommitted());
    }

    @Test
    public void testHead() throws Exception
    {
        Server server = new Server(0);
        try
        {
            server.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    PrintWriter w = response.getWriter();
                    w.flush();
                    w.println("Geht");
                    w.flush();
                    w.println("Doch");
                    w.flush();
                    ((Request)request).setHandled(true);
                }
            });
            server.start();

            Socket socket = new Socket("localhost", ((NetworkConnector)server.getConnectors()[0]).getLocalPort());
            socket.setSoTimeout(500000);
            socket.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
            socket.getOutputStream().flush();

            LineNumberReader reader = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
            String line = reader.readLine();
            Assert.assertThat(line, Matchers.startsWith("HTTP/1.1 200 OK"));
            // look for blank line
            while (line != null && line.length() > 0)
                line = reader.readLine();

            // Read the first line of the GET
            line = reader.readLine();
            Assert.assertThat(line, Matchers.startsWith("HTTP/1.1 200 OK"));

            String last = null;
            while (line != null)
            {
                last = line;
                line = reader.readLine();
            }

            assertEquals("Doch", last);
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testAddCookie() throws Exception
    {
        Response response = newResponse();

        Cookie cookie = new Cookie("name", "value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");

        response.addCookie(cookie);

        String set = response.getHttpFields().getStringField("Set-Cookie");

        assertEquals("name=value;Version=1;Path=/path;Domain=domain;Secure;HttpOnly;Comment=comment", set);
    }


    @Test
    public void testCookiesWithReset() throws Exception
    {
        Response response = newResponse();

        Cookie cookie=new Cookie("name","value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");
        response.addCookie(cookie);

        Cookie cookie2=new Cookie("name2", "value2");
        cookie2.setDomain("domain");
        cookie2.setPath("/path");
        response.addCookie(cookie2);

        //keep the cookies
        response.reset(true);

        Enumeration<String> set = response.getHttpFields().getValues("Set-Cookie");

        assertNotNull(set);
        ArrayList<String> list = Collections.list(set);
        assertEquals(2, list.size());
        assertTrue(list.contains("name=value;Version=1;Path=/path;Domain=domain;Secure;HttpOnly;Comment=comment"));
        assertTrue(list.contains("name2=value2;Path=/path;Domain=domain"));

        //get rid of the cookies
        response.reset();

        set = response.getHttpFields().getValues("Set-Cookie");
        assertFalse(set.hasMoreElements());
    }

    @Test
    public void testFlushAfterFullContent() throws Exception
    {
        Response response = _channel.getResponse();
        byte[] data = new byte[]{(byte)0xCA, (byte)0xFE};
        ServletOutputStream output = response.getOutputStream();
        response.setContentLength(data.length);
        // Write the whole content
        output.write(data);
        // Must not throw
        output.flush();
    }


    @Test
    public void testSetCookie() throws Exception
    {
        Response response = _channel.getResponse();
        HttpFields fields = response.getHttpFields();

        response.addSetCookie("null",null,null,null,-1,null,false,false,-1);
        assertEquals("null=",fields.getStringField("Set-Cookie"));

        fields.clear();
        
        response.addSetCookie("minimal","value",null,null,-1,null,false,false,-1);
        assertEquals("minimal=value",fields.getStringField("Set-Cookie"));

        fields.clear();
        //test cookies with same name, domain and path, only 1 allowed
        response.addSetCookie("everything","wrong","domain","path",0,"to be replaced",true,true,0);
        response.addSetCookie("everything","value","domain","path",0,"comment",true,true,0);
        assertEquals("everything=value;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",fields.getStringField("Set-Cookie"));
        Enumeration<String> e =fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertEquals("everything=value;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT",fields.getStringField("Expires"));
        assertFalse(e.hasMoreElements());
        
        //test cookies with same name, different domain
        fields.clear();
        response.addSetCookie("everything","other","domain1","path",0,"blah",true,true,0);
        response.addSetCookie("everything","value","domain2","path",0,"comment",true,true,0);
        e =fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertEquals("everything=other;Version=1;Path=path;Domain=domain1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=blah",e.nextElement());
        assertTrue(e.hasMoreElements());
        assertEquals("everything=value;Version=1;Path=path;Domain=domain2;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());
        
        //test cookies with same name, same path, one with domain, one without
        fields.clear();
        response.addSetCookie("everything","other","domain1","path",0,"blah",true,true,0);
        response.addSetCookie("everything","value","","path",0,"comment",true,true,0);
        e =fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertEquals("everything=other;Version=1;Path=path;Domain=domain1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=blah",e.nextElement());
        assertTrue(e.hasMoreElements());
        assertEquals("everything=value;Version=1;Path=path;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());
        
        
        //test cookies with same name, different path
        fields.clear();
        response.addSetCookie("everything","other","domain1","path1",0,"blah",true,true,0);
        response.addSetCookie("everything","value","domain1","path2",0,"comment",true,true,0);
        e =fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertEquals("everything=other;Version=1;Path=path1;Domain=domain1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=blah",e.nextElement());
        assertTrue(e.hasMoreElements());
        assertEquals("everything=value;Version=1;Path=path2;Domain=domain1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());
        
        //test cookies with same name, same domain, one with path, one without
        fields.clear();
        response.addSetCookie("everything","other","domain1","path1",0,"blah",true,true,0);
        response.addSetCookie("everything","value","domain1","",0,"comment",true,true,0);
        e =fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertEquals("everything=other;Version=1;Path=path1;Domain=domain1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=blah",e.nextElement());
        assertTrue(e.hasMoreElements());
        assertEquals("everything=value;Version=1;Domain=domain1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());
        
        //test cookies same name only, no path, no domain
        fields.clear();
        response.addSetCookie("everything","other","","",0,"blah",true,true,0);
        response.addSetCookie("everything","value","","",0,"comment",true,true,0);
        e =fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertEquals("everything=value;Version=1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());

        fields.clear();
        response.addSetCookie("ev erything","va lue","do main","pa th",1,"co mment",true,true,1);
        String setCookie=fields.getStringField("Set-Cookie");
        assertThat(setCookie,Matchers.startsWith("\"ev erything\"=\"va lue\";Version=1;Path=\"pa th\";Domain=\"do main\";Expires="));
        assertThat(setCookie,Matchers.endsWith(" GMT;Max-Age=1;Secure;HttpOnly;Comment=\"co mment\""));

        fields.clear();
        response.addSetCookie("name","value",null,null,-1,null,false,false,0);
        setCookie=fields.getStringField("Set-Cookie");
        assertEquals(-1,setCookie.indexOf("Version="));
        fields.clear();
        response.addSetCookie("name","v a l u e",null,null,-1,null,false,false,0);
        setCookie=fields.getStringField("Set-Cookie");

        fields.clear();
        response.addSetCookie("json","{\"services\":[\"cwa\", \"aa\"]}",null,null,-1,null,false,false,-1);
        assertEquals("json=\"{\\\"services\\\":[\\\"cwa\\\", \\\"aa\\\"]}\"",fields.getStringField("Set-Cookie"));

        fields.clear();
        response.addSetCookie("name","value","domain",null,-1,null,false,false,-1);
        response.addSetCookie("name","other","domain",null,-1,null,false,false,-1);
        assertEquals("name=other;Domain=domain",fields.getStringField("Set-Cookie"));
        response.addSetCookie("name","more","domain",null,-1,null,false,false,-1);
        assertEquals("name=more;Domain=domain",fields.getStringField("Set-Cookie"));
        response.addSetCookie("foo","bar","domain",null,-1,null,false,false,-1);
        response.addSetCookie("foo","bob","domain",null,-1,null,false,false,-1);
        assertEquals("name=more;Domain=domain",fields.getStringField("Set-Cookie"));

        e=fields.getValues("Set-Cookie");
        assertEquals("name=more;Domain=domain",e.nextElement());
        assertEquals("foo=bob;Domain=domain",e.nextElement());

        fields.clear();
        response.addSetCookie("name","value%=",null,null,-1,null,false,false,0);
        setCookie=fields.getStringField("Set-Cookie");
        assertEquals("name=value%=",setCookie);

    }
    
    private Response newResponse()
    {
        _channel.reset();
        return new Response(_channel, _channel.getResponse().getHttpOutput());
    }

    private static class TestSession extends HashedSession
    {
        protected TestSession(HashSessionManager hashSessionManager, String id)
        {
            super(hashSessionManager, 0L, 0L, id);
        }
    }
}
