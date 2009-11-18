package org.eclipse.jetty.testing;
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



import java.io.IOException;
import java.net.URL;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.IO;

public class ServletTest extends TestCase
{
    ServletTester tester;
    
    /* ------------------------------------------------------------ */
    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        tester=new ServletTester();
        tester.setContextPath("/context");
        tester.addServlet(TestServlet.class, "/servlet/*");
        tester.addServlet(HelloServlet.class, "/hello/*");
        tester.addServlet(ExceptServlet.class, "/except/*");
        tester.addServlet("org.eclipse.jetty.servlet.DefaultServlet", "/");
        
        tester.start();
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void tearDown() throws Exception
    {
        tester.stop();
        tester=null;
        super.tearDown();
    }

    /* ------------------------------------------------------------ */
    public void testServletTesterRaw() throws Exception
    {
        // Raw HTTP test requests
        String requests=
            "GET /context/servlet/info?query=foo HTTP/1.1\r\n"+
            "Host: tester\r\n"+
            "\r\n"+

            "GET /context/hello HTTP/1.1\r\n"+
            "Host: tester\r\n"+
            "\r\n";

        String responses = tester.getResponses(requests);

        String expected=
            "HTTP/1.1 200 OK\r\n"+
            "Content-Type: text/html;charset=ISO-8859-1\r\n"+
            "Content-Length: 21\r\n"+
            "\r\n"+
            "<h1>Test Servlet</h1>" +

            "HTTP/1.1 200 OK\r\n"+
            "Content-Type: text/html;charset=ISO-8859-1\r\n"+
            "Content-Length: 22\r\n"+
            "\r\n"+
            "<h1>Hello Servlet</h1>";

        assertEquals(expected,responses);
    }

    /* ------------------------------------------------------------ */
    public void testServletTesterClient() throws Exception
    {
        String base_url=tester.createSocketConnector(true);
        
        URL url = new URL(base_url+"/context/hello/info");
        String result = IO.toString(url.openStream());
        assertEquals("<h1>Hello Servlet</h1>",result);
    }

    /* ------------------------------------------------------------ */
    public void testHttpTester() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();
        
        // test GET
        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setURI("/context/hello/info");
        response.parse(tester.getResponses(request.generate()));
        assertTrue(response.getMethod()==null);
        assertEquals(200,response.getStatus());
        assertEquals("<h1>Hello Servlet</h1>",response.getContent());

        // test GET with content
        request.setMethod("POST");
        request.setContent("<pre>Some Test Content</pre>");
        request.setHeader("Content-Type","text/html");
        response.parse(tester.getResponses(request.generate()));
        assertTrue(response.getMethod()==null);
        assertEquals(200,response.getStatus());
        assertEquals("<h1>Hello Servlet</h1><pre>Some Test Content</pre>",response.getContent());
        
        // test redirection
        request.setMethod("GET");
        request.setURI("/context");
        request.setContent(null);
        response.parse(tester.getResponses(request.generate()));
        assertEquals(302,response.getStatus());
        assertEquals("http://tester/context/",response.getHeader("location"));

        // test not found
        request.setURI("/context/xxxx");
        response.parse(tester.getResponses(request.generate()));
        assertEquals(404,response.getStatus());
        
    }


    /* ------------------------------------------------------------ */
    public void testBigPost() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();
        
        String content = "0123456789abcdef";
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+=content;
        content+="!";
        
        request.setMethod("POST");
        request.setVersion("HTTP/1.1");
        request.setURI("/context/hello/info");
        request.setHeader("Host","tester");
        request.setHeader("Content-Type","text/plain");
        request.setContent(content);
        String r=request.generate();
        r = tester.getResponses(r);
        response.parse(r);
        assertTrue(response.getMethod()==null);
        assertEquals(200,response.getStatus());
        assertEquals("<h1>Hello Servlet</h1>"+content,response.getContent());
        
        
    }
    

    /* ------------------------------------------------------------ */
    public void testCharset()
        throws Exception
    {
        byte[] content_iso_8859_1="abcd=1234&AAA=xxx".getBytes("iso8859-1");
        byte[] content_utf_8="abcd=1234&AAA=xxx".getBytes("utf-8");
        byte[] content_utf_16="abcd=1234&AAA=xxx".getBytes("utf-16");

        String request_iso_8859_1=
            "POST /context/servlet/post HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "Content-Type: application/x-www-form-urlencoded\r\n"+
            "Content-Length: "+content_iso_8859_1.length+"\r\n"+
            "\r\n";

        String request_utf_8=
            "POST /context/servlet/post HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "Content-Type: application/x-www-form-urlencoded; charset=utf-8\r\n"+
            "Content-Length: "+content_utf_8.length+"\r\n"+
            "\r\n";

        String request_utf_16=
            "POST /context/servlet/post HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "Content-Type: application/x-www-form-urlencoded; charset=utf-16\r\n"+
            "Content-Length: "+content_utf_16.length+"\r\n"+
            "Connection: close\r\n"+
            "\r\n";
        
        ByteArrayBuffer out = new ByteArrayBuffer(4096);
        out.put(request_iso_8859_1.getBytes("iso8859-1"));
        out.put(content_iso_8859_1);
        out.put(request_utf_8.getBytes("iso8859-1"));
        out.put(content_utf_8);
        out.put(request_utf_16.getBytes("iso8859-1"));
        out.put(content_utf_16);

        ByteArrayBuffer responses = tester.getResponses(out);
        
        String expected=
            "HTTP/1.1 200 OK\r\n"+
            "Content-Type: text/html;charset=ISO-8859-1\r\n"+
            "Content-Length: 21\r\n"+
            "\r\n"+
            "<h1>Test Servlet</h1>"+
            "HTTP/1.1 200 OK\r\n"+
            "Content-Type: text/html;charset=ISO-8859-1\r\n"+
            "Content-Length: 21\r\n"+
            "\r\n"+
            "<h1>Test Servlet</h1>"+
            "HTTP/1.1 200 OK\r\n"+
            "Content-Type: text/html;charset=ISO-8859-1\r\n"+
            "Connection: close\r\n"+
            "\r\n"+
            "<h1>Test Servlet</h1>";
        
        assertEquals(expected,responses.toString());
    }

    /* ------------------------------------------------------------ */
    public void testExcept() throws Exception
    {
        String request0=
            "GET /context/except/io HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "\r\n"+
            "GET /context/except/http HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "\r\n";
        
        ByteArrayBuffer out = new ByteArrayBuffer(4096);
        out.put(request0.getBytes("iso8859-1"));
        String responses = tester.getResponses(out).toString();
        
        int offset = responses.indexOf("HTTP/1.1 500");
        assertTrue(offset>=0);
        offset = responses.indexOf("Content-Length: ",offset);
        assertTrue(offset>0);
        offset = responses.indexOf("<h2>HTTP ERROR 500</h2>",offset);
        assertTrue(offset>0);
        offset = responses.indexOf("IOException: testing",offset);
        assertTrue(offset>0);
        offset = responses.indexOf("</html>",offset);
        assertTrue(offset>0);
        offset = responses.indexOf("HTTP/1.1 499",offset);
        assertTrue(offset>0);
        offset = responses.indexOf("Content-Length: ",offset);
        assertTrue(offset>0);
    }
    
    /* ------------------------------------------------------------ */
    public static class HelloServlet extends HttpServlet
    {
        private static final long serialVersionUID=2779906630657190712L;

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            doGet(request,response);
        }
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/html");
            response.getWriter().print("<h1>Hello Servlet</h1>");
            if (request.getContentLength()>0)
                response.getWriter().write(IO.toString(request.getInputStream()));
        }
    }
    
    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID=2779906630657190712L;

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            assertEquals("/context",request.getContextPath());
            assertEquals("/servlet",request.getServletPath());
            assertEquals("/post",request.getPathInfo());
            assertEquals(2,request.getParameterMap().size());
            assertEquals("1234",request.getParameter("abcd"));
            assertEquals("xxx",request.getParameter("AAA"));
            
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print("<h1>Test Servlet</h1>");
        }
        
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            assertEquals("/context",request.getContextPath());
            assertEquals("/servlet",request.getServletPath());
            assertEquals("/info",request.getPathInfo());
            assertEquals("query=foo",request.getQueryString());
            assertEquals(1,request.getParameterMap().size());
            assertEquals(1,request.getParameterValues("query").length);
            assertEquals("foo",request.getParameter("query"));
            
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print("<h1>Test Servlet</h1>");
        }
    }
    
    public static class ExceptServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if ("/http".equals(request.getPathInfo()))
                throw new HttpException(499);
            if ("/runtime".equals(request.getPathInfo()))
                throw new RuntimeException("testing");
            if ("/error".equals(request.getPathInfo()))
                throw new Error("testing");
            throw new IOException("testing");
        }
    }

}
