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
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.Executor;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncByteArrayEndPoint;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.HashedSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class ResponseTest
{
    private Timer _timer;
    private Server _server;
    private LocalHttpConnector _connector;
    private HttpChannel _channel;
    
    @Before
    public void init() throws Exception
    {
        _timer=new Timer(true);
        _server = new Server();
        _connector = new LocalHttpConnector();
        _server.addConnector(_connector);
        _server.setHandler(new DumpHandler());
        _server.start();
        
        AsyncByteArrayEndPoint endp = new AsyncByteArrayEndPoint(_timer);
        HttpInput input = new HttpInput();
        AsyncConnection connection = new AbstractAsyncConnection(endp,new Executor()
        {
            @Override
            public void execute(Runnable command)
            {
                command.run();                
            }
        })
        {
            @Override
            public void onReadable()
            {                
            }
        };
        
        _channel = new HttpChannel(_server,connection,input)
        {
            @Override
            protected int write(ByteBuffer content) throws IOException
            {
                int length=content.remaining();
                content.clear();
                return length;
            }
            
            @Override
            protected void resetBuffer()
            {
                // TODO Auto-generated method stub
                
            }
            
            @Override
            protected void increaseContentBufferSize(int size)
            {
                // TODO Auto-generated method stub
                
            }
            
            @Override
            public Timer getTimer()
            {
                // TODO Auto-generated method stub
                return null;
            }
            
            @Override
            public HttpConnector getHttpConnector()
            {
                // TODO Auto-generated method stub
                return null;
            }
            
            @Override
            protected int getContentBufferSize()
            {
                // TODO Auto-generated method stub
                return 0;
            }
            
            @Override
            protected void flushResponse() throws IOException
            {
                // TODO Auto-generated method stub
                
            }
            
            @Override
            protected void execute(Runnable task)
            {
                // TODO Auto-generated method stub
                
            }
            
            @Override
            protected void completed()
            {
                // TODO Auto-generated method stub
                
            }
            
            @Override
            protected void completeResponse() throws IOException
            {
                // TODO Auto-generated method stub
                
            }
            
            @Override
            protected void commit(ResponseInfo info, ByteBuffer content) throws IOException
            {
                // TODO Auto-generated method stub
                
            }
        };
    }

    @After
    public void destroy() throws Exception
    {
        _timer.cancel();
        _server.stop();
        _server.join();
    }

    @Test
    public void testContentType() throws Exception
    {
        Response response = newResponse();

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

        Iterator<String> en = response.getHeaders("name").iterator();
        assertEquals("foo",en.next());
        assertFalse(en.hasNext());
        response.addHeader("name","bar");
        en=response.getHeaders("name").iterator();
        assertEquals("foo",en.next());
        assertEquals("bar",en.next());
        assertFalse(en.hasNext());

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
        Response response = newResponse();
        
        ContextHandler context = new ContextHandler();
        context.addLocaleEncoding(Locale.ENGLISH.toString(),"ISO-8859-1");
        context.addLocaleEncoding(Locale.ITALIAN.toString(),"ISO-8859-2");
        response.getHttpChannel().getRequest().setContext(context.getServletContext());
        
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
        Response response = newResponse();

        response.setContentType("foo/bar");
        response.setCharacterEncoding("utf-8");
        assertEquals("foo/bar;charset=UTF-8",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=UTF-8",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=UTF-8",response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=UTF-8",response.getContentType());

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
        Response response = newResponse();
        response.setCharacterEncoding("utf-8");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=UTF-8",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=UTF-8",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=UTF-8",response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=UTF-8",response.getContentType());

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
        Response response = newResponse();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8");
        assertEquals("foo/bar; charset=utf-8",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=UTF-8",response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=UTF-8",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; charset=utf-8");
        assertEquals("text/html; charset=utf-8",response.getContentType());
        response.getWriter();
        assertEquals("text/html; charset=utf-8",response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());

    }

    @Test
    public void testContentTypeWithOther() throws Exception
    {
        Response response = newResponse();

        response.setContentType("foo/bar; other=xyz");
        assertEquals("foo/bar; other=xyz",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=xyz;charset=ISO-8859-1",response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=ISO-8859-1",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html; other=xyz");
        assertEquals("text/html; other=xyz;charset=UTF-8",response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz;charset=UTF-8",response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=UTF-8",response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncodingAndOther() throws Exception
    {
        Response response = newResponse();
        
        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8 other=xyz");
        assertEquals("foo/bar; charset=utf-8 other=xyz",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8 other=xyz",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; other=xyz charset=utf-8");
        assertEquals("text/html; other=xyz charset=utf-8;charset=UTF-16",response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz charset=utf-8;charset=UTF-16",response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; other=pq charset=utf-8 other=xyz");
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz;charset=UTF-16",response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz;charset=UTF-16",response.getContentType());

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
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeader.CACHE_CONTROL.asString()));

        response=newResponse();

        response.setStatus(200);
        assertEquals(200, response.getStatus());
        assertEquals(null, response.getReason());

        response=newResponse();

        response.sendError(406, "Super Nanny");
        assertEquals(406, response.getStatus());
        assertEquals("Super Nanny", response.getReason());
        assertEquals("must-revalidate,no-cache,no-store", response.getHeader(HttpHeader.CACHE_CONTROL.asString()));
    }

    @Test
    public void testEncodeRedirect()
        throws Exception
    {
        Response response=newResponse();
        Request request = response.getHttpChannel().getRequest();
        request.setServerName("myhost");
        request.setServerPort(8888);
        request.setContextPath("/path");

        assertEquals("http://myhost:8888/path/info;param?query=0&more=1#target",response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));

        request.setRequestedSessionId("12345");
        request.setRequestedSessionIdFromCookie(false);
        HashSessionManager manager=new HashSessionManager();
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
            Response response=newResponse();
            Request request = response.getHttpChannel().getRequest();
            
            request.setServerName("myhost");
            request.setServerPort(8888);
            request.setUri(new HttpURI("/path/info;param;jsessionid=12345?query=0&more=1#target"));
            request.setContextPath("/path");
            request.setRequestedSessionId("12345");
            request.setRequestedSessionIdFromCookie(i>0);
            HashSessionManager manager=new HashSessionManager();
            manager.setSessionIdManager(new HashSessionIdManager());
            request.setSessionManager(manager);
            request.setSession(new TestSession(manager,"12345"));
            manager.setCheckingRemoteSessionIdEncoding(false);

            response.sendRedirect(tests[i][0]);

            String location = response.getHeader("Location");
            assertEquals(tests[i][0],tests[i][1],location);
        }
    }

    @Test
    public void testSetBufferSize () throws Exception
    {
        Response response=newResponse();
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
        Server server = new Server(0);
        try
        {
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
                    w.flush();
                    ((Request) request).setHandled(true);
                }
            });
            server.start();

            Socket socket = new Socket("localhost",((Connector.NetConnector)server.getConnectors()[0]).getLocalPort());
            socket.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
            socket.getOutputStream().flush();

            LineNumberReader reader = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
            String line = reader.readLine();
            assertTrue(line!=null && line.startsWith("HTTP/1.1 200 OK"));
            // look for blank line
            while (line!=null && line.length()>0)
                line = reader.readLine();

            // Read the first line of the GET
            line = reader.readLine();
            assertTrue(line!=null && line.startsWith("HTTP/1.1 200 OK"));
            
            String last=null;
            while (line!=null)
            {
                last=line;
                line = reader.readLine();
            }

            assertEquals("Doch",last);
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

        Cookie cookie=new Cookie("name","value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");
        
        response.addCookie(cookie);
        
        String set = response.getHttpFields().getStringField("Set-Cookie");
        
        assertEquals("name=value;Path=/path;Domain=domain;Secure;HttpOnly;Comment=comment",set);
    }

    private Response newResponse()
    {
        Response response = new Response(_channel);
        return response;
    }
    
    private static class TestSession extends HashedSession
    {
        protected TestSession(HashSessionManager hashSessionManager, String id)
        {
            super(hashSessionManager,0L,0L,id);
        }
    }

}
