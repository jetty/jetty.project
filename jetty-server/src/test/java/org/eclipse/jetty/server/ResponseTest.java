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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
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
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.NullSessionDataStore;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionHandler;
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

    static final InetSocketAddress LOCALADDRESS;
    
    static
    {
        InetAddress ip=null;
        try
        {
            ip = Inet4Address.getByName("127.0.0.42");
        }
        catch (UnknownHostException e)
        {
            e.printStackTrace();
        }
        finally
        {
            LOCALADDRESS=new InetSocketAddress(ip,8888);
        }
    }
    
    private Server _server;
    private HttpChannel _channel;

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

        AbstractEndPoint endp = new ByteArrayEndPoint(_scheduler, 5000)
        {
            @Override
            public InetSocketAddress getLocalAddress()
            {
                return LOCALADDRESS;
            }   
        };
        _channel = new HttpChannel(connector, new HttpConfiguration(), endp, new HttpTransport()
        {
            private Throwable _channelError;

            @Override
            public void send(MetaData.Response info, boolean head, ByteBuffer content, boolean lastContent, Callback callback)
            {
                if (_channelError==null)
                    callback.succeeded();
                else
                    callback.failed(_channelError);
            }

            @Override
            public boolean isPushSupported()
            {
                return false;
            }

            @Override
            public void push(org.eclipse.jetty.http.MetaData.Request request)
            {
            }

            @Override
            public void onCompleted()
            {
            }

            @Override
            public void abort(Throwable failure)
            {
                _channelError=failure;
            }

            @Override
            public boolean isOptimizedForDirectBuffers()
            {
                return false;
            }
        });
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
        Response response = getResponse();

        assertEquals(null, response.getContentType());

        response.setHeader("Content-Type", "text/something");
        assertEquals("text/something", response.getContentType());

        response.setContentType("foo/bar");
        assertEquals("foo/bar", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=iso-8859-1", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=iso-8859-1", response.getContentType());
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
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("foo2/bar2;charset=utf-8");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();
        response.setContentType("text/xml;charset=ISO-8859-7");
        response.getWriter();
        assertEquals("text/xml;charset=ISO-8859-7", response.getContentType());
        response.setContentType("text/html;charset=UTF-8");
        assertEquals("text/html;charset=ISO-8859-7", response.getContentType());

        response.recycle();
        response.setContentType("text/html;charset=US-ASCII");
        response.getWriter();
        assertEquals("text/html;charset=US-ASCII", response.getContentType());

        response.recycle();
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());

        response.recycle();
        response.setContentType("text/json");
        response.getWriter();
        assertEquals("text/json", response.getContentType());

        response.recycle();
        response.setContentType("text/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter();
        assertEquals("text/json;charset=utf-8", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=xyz", response.getContentType());

        response.recycle();
        response.setContentType("foo/bar");
        response.setCharacterEncoding("xyz");
        assertEquals("foo/bar;charset=xyz", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar;charset=abc");
        assertEquals("foo/bar;charset=abc", response.getContentType());

        response.recycle();
        response.setContentType("foo/bar;charset=abc");
        response.setCharacterEncoding("xyz");
        assertEquals("foo/bar;charset=xyz", response.getContentType());

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
    public void testStrangeContentType() throws Exception
    {
        Response response = getResponse();

        assertEquals(null, response.getContentType());

        response.recycle();
        response.setContentType("text/html;charset=utf-8;charset=UTF-8");
        response.getWriter();
        assertEquals("text/html;charset=utf-8;charset=UTF-8",response.getContentType());
        assertEquals("utf-8",response.getCharacterEncoding().toLowerCase(Locale.ENGLISH));
    }

    @Test
    public void testLocale() throws Exception
    {
        Response response = getResponse();

        ContextHandler context = new ContextHandler();
        context.addLocaleEncoding(Locale.ENGLISH.toString(), "ISO-8859-1");
        context.addLocaleEncoding(Locale.ITALIAN.toString(), "ISO-8859-2");
        response.getHttpChannel().getRequest().setContext(context.getServletContext());

        response.setLocale(java.util.Locale.ITALIAN);
        assertEquals(null, response.getContentType());
        response.setContentType("text/plain");
        assertEquals("text/plain;charset=ISO-8859-2", response.getContentType());

        response.recycle();
        response.setContentType("text/plain");
        response.setCharacterEncoding("utf-8");
        response.setLocale(java.util.Locale.ITALIAN);
        assertEquals("text/plain;charset=utf-8", response.getContentType());
        assertTrue(response.toString().indexOf("charset=utf-8") > 0);
    }

    @Test
    public void testContentTypeCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setContentType("foo/bar");
        response.setCharacterEncoding("utf-8");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
    }

    @Test
    public void testCharacterEncodingContentType() throws Exception
    {
        Response response = getResponse();
        response.setCharacterEncoding("utf-8");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html");
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=UTF-8");
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; charset=utf-8");
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
        
        response.recycle();
        response.setCharacterEncoding("utf-16");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-16", response.getContentType());
        response.getOutputStream();
        response.setCharacterEncoding("utf-8");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.flushBuffer();
        response.setCharacterEncoding("utf-16");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
    }

    @Test
    public void testResetWithNewSession() throws Exception
    {
        Response response = getResponse();
        Request request = response.getHttpChannel().getRequest();
        
        SessionHandler session_handler = new SessionHandler();
        session_handler.setServer(_server);
        session_handler.setUsingCookies(true);
        session_handler.start();
        request.setSessionHandler(session_handler);
        HttpSession session = request.getSession(true);
        
        assertThat(session,not(nullValue()));
        assertTrue(session.isNew());
        
        HttpField set_cookie = response.getHttpFields().getField(HttpHeader.SET_COOKIE);
        assertThat(set_cookie,not(nullValue()));
        assertThat(set_cookie.getValue(),startsWith("JSESSIONID"));
        assertThat(set_cookie.getValue(),containsString(session.getId()));
        response.setHeader("Some","Header");
        response.addCookie(new Cookie("Some","Cookie"));
        response.getOutputStream().print("X");
        assertThat(response.getHttpFields().size(),is(4));
        
        response.reset();
        
        set_cookie = response.getHttpFields().getField(HttpHeader.SET_COOKIE);
        assertThat(set_cookie,not(nullValue()));
        assertThat(set_cookie.getValue(),startsWith("JSESSIONID"));
        assertThat(set_cookie.getValue(),containsString(session.getId()));
        assertThat(response.getHttpFields().size(),is(2));
        response.getWriter();
    }
    
    @Test
    public void testResetContentTypeWithoutCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setCharacterEncoding("utf-8");
        response.setContentType("wrong/answer");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.getWriter();
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
    }


    @Test
    public void testResetContentTypeWithCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setContentType("wrong/answer;charset=utf-8");
        response.setContentType("foo/bar");
        assertEquals("foo/bar", response.getContentType());
        response.setContentType("wrong/answer;charset=utf-8");
        response.getWriter();
        response.setContentType("foo2/bar2;charset=utf-16");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
    }

    
    
    
    @Test
    public void testContentTypeWithOther() throws Exception
    {
        Response response = getResponse();

        response.setContentType("foo/bar; other=xyz");
        assertEquals("foo/bar; other=xyz", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=xyz;charset=iso-8859-1", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=iso-8859-1", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("uTf-8");
        response.setContentType("text/html; other=xyz");
        assertEquals("text/html; other=xyz;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncodingAndOther() throws Exception
    {
        Response response = getResponse();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8 other=xyz");
        assertEquals("foo/bar; charset=utf-8 other=xyz", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8 other=xyz", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; other=xyz charset=utf-8");
        assertEquals("text/html; other=xyz charset=utf-8;charset=utf-16", response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz charset=utf-8;charset=utf-16", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; other=pq charset=utf-8 other=xyz");
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz;charset=utf-16", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz;charset=utf-16", response.getContentType());
    }

    @Test
    public void testStatusCodes() throws Exception
    {
        Response response = getResponse();

        response.sendError(404);
        assertEquals(404, response.getStatus());
        assertEquals("Not Found", response.getReason());

        response = getResponse();

        response.sendError(500, "Database Error");
        assertEquals(500, response.getStatus());
        assertEquals("Database Error", response.getReason());
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeader.CACHE_CONTROL.asString()));

        response = getResponse();

        response.setStatus(200);
        assertEquals(200, response.getStatus());
        assertEquals(null, response.getReason());

        response = getResponse();

        response.sendError(406, "Super Nanny");
        assertEquals(406, response.getStatus());
        assertEquals("Super Nanny", response.getReason());
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeader.CACHE_CONTROL.asString()));
    }
    
    @Test
    public void testStatusCodesNoErrorHandler() throws Exception
    {
        _server.removeBean(_server.getBean(ErrorHandler.class));
        Response response = getResponse();

        response.sendError(404);
        assertEquals(404, response.getStatus());
        assertEquals("Not Found", response.getReason());

        response = getResponse();

        response.sendError(500, "Database Error");
        assertEquals(500, response.getStatus());
        assertEquals("Database Error", response.getReason());
        assertThat(response.getHeader(HttpHeader.CACHE_CONTROL.asString()),Matchers.nullValue());

        response = getResponse();

        response.setStatus(200);
        assertEquals(200, response.getStatus());
        assertEquals(null, response.getReason());

        response = getResponse();

        response.sendError(406, "Super Nanny");
        assertEquals(406, response.getStatus());
        assertEquals("Super Nanny", response.getReason());
        assertThat(response.getHeader(HttpHeader.CACHE_CONTROL.asString()),Matchers.nullValue());
    }

    @Test
    public void testWriteRuntimeIOException() throws Exception
    {
        Response response = getResponse();

        PrintWriter writer = response.getWriter();
        writer.println("test");
        writer.flush();
        Assert.assertFalse(writer.checkError());

        Throwable cause = new IOException("problem at mill");
        _channel.abort(cause);
        writer.println("test");
        Assert.assertTrue(writer.checkError());
        try
        {
            writer.println("test");
            Assert.fail();
        }
        catch(RuntimeIOException e)
        {
            Assert.assertEquals(cause,e.getCause());
        }

    }

    @Test
    public void testEncodeRedirect()
            throws Exception
    {
        Response response = getResponse();
        Request request = response.getHttpChannel().getRequest();
        request.setAuthority("myhost",8888);
        request.setContextPath("/path");

        assertEquals("http://myhost:8888/path/info;param?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));

        request.setRequestedSessionId("12345");
        request.setRequestedSessionIdFromCookie(false);
        SessionHandler handler = new SessionHandler();
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        NullSessionDataStore ds = new NullSessionDataStore();
        ss.setSessionDataStore(ds);
        handler.setSessionIdManager(new DefaultSessionIdManager(_server));
        request.setSessionHandler(handler);
        TestSession tsession = new TestSession(handler, "12345");
        tsession.setExtendedId(handler.getSessionIdManager().getExtendedId("12345", null));
        request.setSession(tsession);

        handler.setCheckingRemoteSessionIdEncoding(false);

        assertEquals("http://myhost:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost:8888/other/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/other/info;param?query=0&more=1#target"));

        handler.setCheckingRemoteSessionIdEncoding(true);
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
        handler.setCheckingRemoteSessionIdEncoding(false);
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
                {"../locati%C3%abn", "http://@HOST@@PORT@/locati%C3%abn"},
                {"../other%2fplace", "http://@HOST@@PORT@/other%2fplace"},
                {"http://somehost.com/other/location","http://somehost.com/other/location"},
        };

        int[] ports=new int[]{8080,80};
        String[] hosts=new String[]{null,"myhost","192.168.0.1","0::1"};
        for (int port : ports)
        {
            for (String host : hosts)
            {
                for (int i=0;i<tests.length;i++)
                {
                    // System.err.printf("%s %d %s%n",host,port,tests[i][0]);
                    
                    Response response = getResponse();
                    Request request = response.getHttpChannel().getRequest();

                    request.setScheme("http");
                    if (host!=null)
                        request.setAuthority(host,port);
                    request.setURIPathQuery("/path/info;param;jsessionid=12345?query=0&more=1#target");
                    request.setContextPath("/path");
                    request.setRequestedSessionId("12345");
                    request.setRequestedSessionIdFromCookie(i>2);
                    SessionHandler handler = new SessionHandler();
                    
                    NullSessionDataStore ds = new NullSessionDataStore();
                    DefaultSessionCache ss = new DefaultSessionCache(handler);
                    handler.setSessionCache(ss);
                    ss.setSessionDataStore(ds);
                    handler.setSessionIdManager(new DefaultSessionIdManager(_server));
                    request.setSessionHandler(handler);
                    request.setSession(new TestSession(handler, "12345"));
                    handler.setCheckingRemoteSessionIdEncoding(false);

                    response.sendRedirect(tests[i][0]);

                    String location = response.getHeader("Location");
                    
                    String expected = tests[i][1]
                        .replace("@HOST@",host==null ? request.getLocalAddr() : (host.contains(":")?("["+host+"]"):host ))
                        .replace("@PORT@",host==null ? ":8888" : (port==80?"":(":"+port)));
                    assertEquals("test-"+i+" "+host+":"+port,expected,location);
                }
            }
        }
    }

    @Test
    public void testSetBufferSizeAfterHavingWrittenContent() throws Exception
    {
        Response response = getResponse();
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
        Response response = getResponse();
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

            try(Socket socket = new Socket("localhost", ((NetworkConnector)server.getConnectors()[0]).getLocalPort()))
            {
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
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testAddCookie() throws Exception
    {
        Response response = getResponse();

        Cookie cookie = new Cookie("name", "value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");

        response.addCookie(cookie);

        String set = response.getHttpFields().get("Set-Cookie");

        assertEquals("name=value;Version=1;Path=/path;Domain=domain;Secure;HttpOnly;Comment=comment", set);
    }


    @Test
    public void testCookiesWithReset() throws Exception
    {
        Response response = getResponse();

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
        Response response = getResponse();
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
        assertEquals("null=",fields.get("Set-Cookie"));

        fields.clear();

        response.addSetCookie("minimal","value",null,null,-1,null,false,false,-1);
        assertEquals("minimal=value",fields.get("Set-Cookie"));

        fields.clear();
        //test cookies with same name, domain and path
        response.addSetCookie("everything","something","domain","path",0,"noncomment",true,true,0);
        response.addSetCookie("everything","value","domain","path",0,"comment",true,true,0);
        Enumeration<String> e =fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertEquals("everything=something;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=noncomment",e.nextElement());
        assertEquals("everything=value;Version=1;Path=path;Domain=domain;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT",fields.get("Expires"));
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
        assertEquals("everything=other;Version=1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=blah",e.nextElement());
        assertEquals("everything=value;Version=1;Expires=Thu, 01-Jan-1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;Comment=comment",e.nextElement());
        assertFalse(e.hasMoreElements());

        fields.clear();
        response.addSetCookie("ev erything","va lue","do main","pa th",1,"co mment",true,true,1);
        String setCookie=fields.get("Set-Cookie");
        assertThat(setCookie,Matchers.startsWith("\"ev erything\"=\"va lue\";Version=1;Path=\"pa th\";Domain=\"do main\";Expires="));
        assertThat(setCookie,Matchers.endsWith(" GMT;Max-Age=1;Secure;HttpOnly;Comment=\"co mment\""));

        fields.clear();
        response.addSetCookie("name","value",null,null,-1,null,false,false,0);
        setCookie=fields.get("Set-Cookie");
        assertEquals(-1,setCookie.indexOf("Version="));
        fields.clear();
        response.addSetCookie("name","v a l u e",null,null,-1,null,false,false,0);
        setCookie=fields.get("Set-Cookie");

        fields.clear();
        response.addSetCookie("json","{\"services\":[\"cwa\", \"aa\"]}",null,null,-1,null,false,false,-1);
        assertEquals("json=\"{\\\"services\\\":[\\\"cwa\\\", \\\"aa\\\"]}\"",fields.get("Set-Cookie"));

        fields.clear();
        response.addSetCookie("name","value","domain",null,-1,null,false,false,-1);
        response.addSetCookie("name","other","domain",null,-1,null,false,false,-1);
        response.addSetCookie("name","more","domain",null,-1,null,false,false,-1);
        e = fields.getValues("Set-Cookie");
        assertTrue(e.hasMoreElements());
        assertThat(e.nextElement(), Matchers.startsWith("name=value"));
        assertThat(e.nextElement(), Matchers.startsWith("name=other"));
        assertThat(e.nextElement(), Matchers.startsWith("name=more"));

        response.addSetCookie("foo","bar","domain",null,-1,null,false,false,-1);
        response.addSetCookie("foo","bob","domain",null,-1,null,false,false,-1);
        assertThat(fields.get("Set-Cookie"), Matchers.startsWith("name=value"));


        fields.clear();
        response.addSetCookie("name","value%=",null,null,-1,null,false,false,0);
        setCookie=fields.get("Set-Cookie");
        assertEquals("name=value%=",setCookie);
    }

    private Response getResponse()
    {
        _channel.recycle();
        _channel.getRequest().setMetaData(new MetaData.Request("GET",new HttpURI("/path/info"),HttpVersion.HTTP_1_0,new HttpFields()));
        return _channel.getResponse();
    }

    private static class TestSession extends Session
    {
        protected TestSession(SessionHandler handler, String id)
        {
            super(handler, new SessionData(id, "", "0.0.0.0", 0, 0, 0, 300));
        }
    }
}
