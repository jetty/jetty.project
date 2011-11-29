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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionContext;

import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.Parser;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class ResponseTest
{
    private Server server;
    private LocalConnector connector;
    
    @Before
    public void init() throws Exception
    {
        server = new Server();
        connector = new LocalConnector();
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        server.start();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testContentType() throws Exception
    {
        AbstractHttpConnection connection = new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer());
        Response response = connection.getResponse();

        assertEquals(null,response.getContentType());

        response.setHeader("Content-Type","text/something");
        assertEquals("text/something",response.getContentType());
        
        response.setContentType("foo/bar");
        assertEquals("foo/bar",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=ISO-8859-1",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=ISO-8859-1",response.getContentType());
        response.setHeader("name","foo");
        Enumeration<?> en=response.getHeaders("name");
        assertEquals("foo",en.nextElement());
        assertFalse(en.hasMoreElements());
        response.addHeader("name","bar");
        en=response.getHeaders("name");
        assertEquals("foo",en.nextElement());
        assertEquals("bar",en.nextElement());
        assertFalse(en.hasMoreElements());

        response.recycle();

        response.setContentType("text/html");
        assertEquals("text/html",response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=ISO-8859-1",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=ISO-8859-1",response.getContentType());

        response.recycle();
        response.setContentType("text/xml;charset=ISO-8859-7");
        response.getWriter();
        response.setContentType("text/html;charset=UTF-8");
        assertEquals("text/html;charset=ISO-8859-7",response.getContentType());
        
        response.recycle();
        response.setContentType("text/html;charset=US-ASCII");
        response.getWriter();
        assertEquals("text/html;charset=US-ASCII",response.getContentType());
        
        response.recycle();
        response.setContentType("text/json");
        response.getWriter();
        assertEquals("text/json;charset=UTF-8", response.getContentType());
    }

    @Test
    public void testLocale() throws Exception
    {

        AbstractHttpConnection connection = new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer());
        Request request = connection.getRequest();
        Response response = connection.getResponse();
        ContextHandler context = new ContextHandler();
        context.addLocaleEncoding(Locale.ENGLISH.toString(),"ISO-8859-1");
        context.addLocaleEncoding(Locale.ITALIAN.toString(),"ISO-8859-2");
        request.setContext(context.getServletContext());

        response.setLocale(java.util.Locale.ITALIAN);
        assertEquals(null,response.getContentType());
        response.setContentType("text/plain");
        assertEquals("text/plain;charset=ISO-8859-2",response.getContentType());

        response.recycle();
        response.setContentType("text/plain");
        response.setCharacterEncoding("utf-8");
        response.setLocale(java.util.Locale.ITALIAN);
        assertEquals("text/plain;charset=UTF-8",response.getContentType());
        assertTrue(response.toString().indexOf("charset=UTF-8")>0);
    }

    @Test
    public void testContentTypeCharacterEncoding() throws Exception
    {
        AbstractHttpConnection connection = new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer());

        Response response = connection.getResponse();


        response.setContentType("foo/bar");
        response.setCharacterEncoding("utf-8");
        assertEquals("foo/bar;charset=utf-8",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=utf-8",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8",response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8",response.getContentType());

        response.recycle();

        response.setContentType("text/html");
        response.setCharacterEncoding("utf-8");
        assertEquals("text/html;charset=UTF-8",response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=UTF-8",response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());

    }

    @Test
    public void testCharacterEncodingContentType() throws Exception
    {
        Response response = new Response(new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer()));

        response.setCharacterEncoding("utf-8");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-8",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=utf-8",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8",response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html");
        assertEquals("text/html;charset=UTF-8",response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=UTF-8",response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());

    }

    @Test
    public void testContentTypeWithCharacterEncoding() throws Exception
    {
        Response response = new Response(new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer()));

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8");
        assertEquals("foo/bar; charset=utf-8",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8",response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; charset=utf-8");
        assertEquals("text/html;charset=UTF-8",response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=UTF-8",response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());

    }

    @Test
    public void testContentTypeWithOther() throws Exception
    {
        Response response = new Response(new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer()));

        response.setContentType("foo/bar; other=xyz");
        assertEquals("foo/bar; other=xyz",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=xyz;charset=ISO-8859-1",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=ISO-8859-1",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html; other=xyz");
        assertEquals("text/html; other=xyz;charset=utf-8",response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz;charset=utf-8",response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncodingAndOther() throws Exception
    {
        Response response = new Response(new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer()));

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8 other=xyz");
        assertEquals("foo/bar; charset=utf-8 other=xyz",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8 other=xyz",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; other=xyz charset=utf-8");
        assertEquals("text/html; other=xyz charset=utf-8",response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz charset=utf-8",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; other=pq charset=utf-8 other=xyz");
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz",response.getContentType());

    }

    @Test
    public void testStatusCodes() throws Exception
    {
        Response response=newResponse();

        response.sendError(404);
        assertEquals(404, response.getStatus());
        assertEquals(null, response.getReason());

        response=newResponse();

        response.sendError(500, "Database Error");
        assertEquals(500, response.getStatus());
        assertEquals("Database Error", response.getReason());
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));

        response=newResponse();

        response.setStatus(200);
        assertEquals(200, response.getStatus());
        assertEquals(null, response.getReason());

        response=newResponse();

        response.sendError(406, "Super Nanny");
        assertEquals(406, response.getStatus());
        assertEquals("Super Nanny", response.getReason());
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    public void testEncodeRedirect()
        throws Exception
    {
        AbstractHttpConnection connection=new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer());
        Response response = new Response(connection);
        Request request = connection.getRequest();
        request.setServerName("myhost");
        request.setServerPort(8888);
        request.setContextPath("/path");

        assertEquals("http://myhost:8888/path/info;param?query=0&more=1#target",response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));

        request.setRequestedSessionId("12345");
        request.setRequestedSessionIdFromCookie(false);
        AbstractSessionManager manager=new HashSessionManager();
        manager.setSessionIdManager(new HashSessionIdManager());
        request.setSessionManager(manager);
        request.setSession(new TestSession(manager,"12345"));

        manager.setCheckingRemoteSessionIdEncoding(false);

        assertEquals("http://myhost:8888/path/info;param;jsessionid=12345?query=0&more=1#target",response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param;jsessionid=12345?query=0&more=1#target",response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost/path/info;param;jsessionid=12345?query=0&more=1#target",response.encodeURL("http://myhost/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost:8888/other/info;param;jsessionid=12345?query=0&more=1#target",response.encodeURL("http://myhost:8888/other/info;param?query=0&more=1#target"));

        manager.setCheckingRemoteSessionIdEncoding(true);
        assertEquals("http://myhost:8888/path/info;param;jsessionid=12345?query=0&more=1#target",response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param?query=0&more=1#target",response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost/path/info;param?query=0&more=1#target",response.encodeURL("http://myhost/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost:8888/other/info;param?query=0&more=1#target",response.encodeURL("http://myhost:8888/other/info;param?query=0&more=1#target"));
        
        request.setContextPath("");
        assertEquals("http://myhost:8888/;jsessionid=12345",response.encodeURL("http://myhost:8888"));
        assertEquals("https://myhost:8888/;jsessionid=12345",response.encodeURL("https://myhost:8888"));    
        assertEquals("mailto:/foo", response.encodeURL("mailto:/foo"));
        assertEquals("http://myhost:8888/;jsessionid=12345",response.encodeURL("http://myhost:8888/"));
        assertEquals("http://myhost:8888/;jsessionid=12345", response.encodeURL("http://myhost:8888/;jsessionid=7777"));
        assertEquals("http://myhost:8888/;param;jsessionid=12345?query=0&more=1#target",response.encodeURL("http://myhost:8888/;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param?query=0&more=1#target",response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
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
        String[][] tests={
                {"/other/location?name=value","http://myhost:8888/other/location;jsessionid=12345?name=value"},
                {"/other/location","http://myhost:8888/other/location"},
                {"/other/l%20cation","http://myhost:8888/other/l%20cation"},
                {"location","http://myhost:8888/path/location"},
                {"./location","http://myhost:8888/path/location"},
                {"../location","http://myhost:8888/location"},
                {"/other/l%20cation","http://myhost:8888/other/l%20cation"},
                {"l%20cation","http://myhost:8888/path/l%20cation"},
                {"./l%20cation","http://myhost:8888/path/l%20cation"},
                {"../l%20cation","http://myhost:8888/l%20cation"},
        };
        
        for (int i=1;i<tests.length;i++)
        {
            ByteArrayEndPoint out=new ByteArrayEndPoint(new byte[]{},4096);
            AbstractHttpConnection connection=new TestHttpConnection(connector,out, connector.getServer());
            Response response = new Response(connection);
            Request request = connection.getRequest();
            request.setServerName("myhost");
            request.setServerPort(8888);
            request.setUri(new HttpURI("/path/info;param;jsessionid=12345?query=0&more=1#target"));
            request.setContextPath("/path");
            request.setRequestedSessionId("12345");
            request.setRequestedSessionIdFromCookie(i>0);
            AbstractSessionManager manager=new HashSessionManager();
            manager.setSessionIdManager(new HashSessionIdManager());
            request.setSessionManager(manager);
            request.setSession(new TestSession(manager,"12345"));
            manager.setCheckingRemoteSessionIdEncoding(false);

            response.sendRedirect(tests[i][0]);

            String location = out.getOut().toString();
            int l=location.indexOf("Location: ");
            int e=location.indexOf('\n',l);
            location=location.substring(l+10,e).trim();
            assertEquals(tests[i][0],tests[i][1],location);
        }
    }

    @Test
    public void testSetBufferSize () throws Exception
    {
        Response response = new Response(new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer()));
        response.setBufferSize(20*1024);
        response.getWriter().print("hello");
        try
        {
            response.setBufferSize(21*1024);
            fail("Expected IllegalStateException on Request.setBufferSize");
        }
        catch (Exception e)
        {
            assertTrue(e instanceof IllegalStateException);
        }
    }

    @Test
    public void testHead() throws Exception
    {
        Server server = new Server();
        try
        {
            SocketConnector socketConnector = new SocketConnector();
            socketConnector.setPort(0);
            server.addConnector(socketConnector);
            server.setHandler(new AbstractHandler()
            {
                public void handle(String string, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    PrintWriter w = response.getWriter();
                    w.flush();
                    w.println("Geht");
                    w.flush();
                    w.println("Doch");
                    ((Request) request).setHandled(true);
                }
            });
            server.start();

            Socket socket = new Socket("localhost",socketConnector.getLocalPort());
            socket.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
            socket.getOutputStream().flush();

            LineNumberReader reader = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
            String line = reader.readLine();
            while (line!=null && line.length()>0)
                line = reader.readLine();

            while (line!=null && line.length()==0)
                line = reader.readLine();

            assertTrue(line!=null && line.startsWith("HTTP/1.1 200 OK"));

        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testAddCookie() throws Exception
    {
        Response response = new Response(new TestHttpConnection(connector,new ByteArrayEndPoint(), connector.getServer()));

        Cookie cookie=new Cookie("name","value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");
        
        response.addCookie(cookie);
        
        String set = response.getHttpFields().getStringField("Set-Cookie");
        
        assertEquals("name=value;Path=/path;Domain=domain;Secure;HttpOnly",set);
    }

    private Response newResponse()
    {
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        endPoint.setOut(new ByteArrayBuffer(1024));
        endPoint.setGrowOutput(true);
        AbstractHttpConnection connection=new TestHttpConnection(connector, endPoint, connector.getServer());
        connection.getGenerator().reset();
        AbstractHttpConnection.setCurrentConnection(connection);
        Response response = connection.getResponse();
        connection.getRequest().setRequestURI("/test");
        return response;
    }

    private class TestSession extends AbstractSession
    {
        public TestSession(AbstractSessionManager abstractSessionManager, String id)
        {
            super(abstractSessionManager, System.currentTimeMillis(),System.currentTimeMillis(), id);
        }

        public Object getAttribute(String name)
        {
            return null;
        }

        public Enumeration getAttributeNames()
        {

            return null;
        }

        public long getCreationTime()
        {

            return 0;
        }

        public String getId()
        {
            return "12345";
        }

        public long getLastAccessedTime()
        {
            return 0;
        }

        public int getMaxInactiveInterval()
        {
            return 0;
        }

        public ServletContext getServletContext()
        {
            return null;
        }

        public HttpSessionContext getSessionContext()
        {
            return null;
        }

        public Object getValue(String name)
        {
            return null;
        }

        public String[] getValueNames()
        {
            return null;
        }

        public void invalidate()
        {
        }

        public boolean isNew()
        {
            return false;
        }

        public void putValue(String name, Object value)
        {
        }

        public void removeAttribute(String name)
        {
        }

        public void removeValue(String name)
        {
        }

        public void setAttribute(String name, Object value)
        {
        }

        public void setMaxInactiveInterval(int interval)
        {
        }

        protected Map newAttributeMap()
        {
            return null;
        }
    }
    
    static class TestHttpConnection extends AbstractHttpConnection
    {
        
        public TestHttpConnection(Connector connector, EndPoint endpoint, Server server)
        {
            super(connector,endpoint,server);
        }

        public TestHttpConnection(Connector connector, EndPoint endpoint, Server server, Parser parser, Generator generator, Request request)
        {
            super(connector,endpoint,server,parser,generator,request);
        }

        @Override
        public Connection handle() throws IOException
        {
            return this;
        }
        
    }
}
