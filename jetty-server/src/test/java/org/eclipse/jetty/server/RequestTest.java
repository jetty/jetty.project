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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MultiPartInputStreamParser;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RequestTest
{
    private static final Logger LOG = Log.getLogger(RequestTest.class);
    private Server _server;
    private LocalConnector _connector;
    private RequestHandler _handler;

    @Before
    public void init() throws Exception
    {
        _server = new Server();
        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        http.getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());
        _connector = new LocalConnector(_server,http);
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
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                Map<String,String[]> map = null;
                //do the parse
                map = request.getParameterMap();
                assertEquals("aaa"+Utf8Appendable.REPLACEMENT+"bbb",map.get("param")[0]);
                assertEquals("value",map.get("other")[0]);

                return true;
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request="GET /?param=aaa%ZZbbb&other=value HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: text/html;charset=utf8\n"+
        "Connection: close\n"+
        "\n";

        String responses=_connector.getResponses(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));

    }

    @Test
    public void testEmptyHeaders() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                assertNotNull(request.getLocale());
                assertTrue(request.getLocales().hasMoreElements());
                assertNull(request.getContentType());
                assertNull(request.getCharacterEncoding());
                assertEquals(0,request.getQueryString().length());
                assertEquals(-1,request.getContentLength());
                assertNull(request.getCookies());
                assertNull(request.getHeader("Name"));
                assertFalse(request.getHeaders("Name").hasMoreElements());
                assertEquals(-1,request.getDateHeader("Name"));
                return true;
            }
        };

        String request="GET /? HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Connection: close\n"+
        "Content-Type: \n"+
        "Accept-Language: \n"+
        "Cookie: \n"+
        "Name: \n"+
        "\n";

        String responses=_connector.getResponses(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testMultiPartNoConfig() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                try
                {
                    Part foo = request.getPart("stuff");
                    return false;
                }
                catch (IllegalStateException e)
                {
                    //expected exception because no multipart config is set up
                    assertTrue(e.getMessage().startsWith("No multipart config"));
                    return true;
                }
                catch (Exception e)
                {
                    return false;
                }
            }
        };

        String multipart =  "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"field1\"\r\n"+
        "\r\n"+
        "Joe Blow\r\n"+
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"stuff\"\r\n"+
        "Content-Type: text/plain;charset=ISO-8859-1\r\n"+
        "\r\n"+
        "000000000000000000000000000000000000000000000000000\r\n"+
        "--AaB03x--\r\n";

        String request="GET / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n"+
        "Content-Length: "+multipart.getBytes().length+"\r\n"+
        "Connection: close\r\n"+
        "\r\n"+
        multipart;

        String responses=_connector.getResponses(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }


    @Test
    public void testMultiPart() throws Exception
    {        
        final File testTmpDir = File.createTempFile("reqtest", null);
        if (testTmpDir.exists())
            testTmpDir.delete();
        testTmpDir.mkdir();
        testTmpDir.deleteOnExit();
        assertTrue(testTmpDir.list().length == 0);

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new MultiPartRequestHandler(testTmpDir));
        contextHandler.addEventListener(new Request.MultiPartCleanerListener()
        {

            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                MultiPartInputStreamParser m = (MultiPartInputStreamParser)sre.getServletRequest().getAttribute(Request.__MULTIPART_INPUT_STREAM);
                ContextHandler.Context c = (ContextHandler.Context)sre.getServletRequest().getAttribute(Request.__MULTIPART_CONTEXT);
                assertNotNull (m);
                assertNotNull (c);
                assertTrue(c == sre.getServletContext());
                assertTrue(!m.getParsedParts().isEmpty());
                assertTrue(testTmpDir.list().length == 2);
                super.requestDestroyed(sre);
                String[] files = testTmpDir.list();
                assertTrue(files.length == 0);
            }

        });
        _server.stop();
        _server.setHandler(contextHandler);
        _server.start();

        String multipart =  "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"field1\"\r\n"+
        "\r\n"+
        "Joe Blow\r\n"+
        "--AaB03x\r\n"+
        "content-disposition: form-data; name=\"stuff\"; filename=\"foo.upload\"\r\n"+
        "Content-Type: text/plain;charset=ISO-8859-1\r\n"+
        "\r\n"+
        "000000000000000000000000000000000000000000000000000\r\n"+
        "--AaB03x--\r\n";

        String request="GET /foo/x.html HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n"+
        "Content-Length: "+multipart.getBytes().length+"\r\n"+
        "Connection: close\r\n"+
        "\r\n"+
        multipart;

        String responses=_connector.getResponses(request);
        // System.err.println(responses);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testBadUtf8ParamExtraction() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
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
        "Connection: close\n"+
        "\n";

        LOG.info("Expecting NotUtf8Exception byte 62 in state 3...");
        String responses=_connector.getResponses(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testInvalidHostHeader() throws Exception
    {
        // Use a contextHandler with vhosts to force call to Request.getServerName()
        ContextHandler context = new ContextHandler();
        context.addVirtualHosts(new String[]{"something"});
        _server.stop();
        _server.setHandler(context);
        _server.start();


        // Request with illegal Host header
        String request="GET / HTTP/1.1\n"+
        "Host: whatever.com:xxxx\n"+
        "Content-Type: text/html;charset=utf8\n"+
        "Connection: close\n"+
        "\n";

        String responses=_connector.getResponses(request);
        assertThat(responses,Matchers.startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testContentTypeEncoding() throws Exception
    {
        final ArrayList<String> results = new ArrayList<String>();
        _handler._checker = new RequestTester()
        {
            @Override
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
                "Connection: close\n"+
                "\n"
                );

        int i=0;
        assertEquals("text/test",results.get(i++));
        assertEquals(null,results.get(i++));

        assertEquals("text/html;charset=utf8",results.get(i++));
        assertEquals("UTF-8",results.get(i++));

        assertEquals("text/html; charset=\"utf8\"",results.get(i++));
        assertEquals("UTF-8",results.get(i++));

        assertTrue(results.get(i++).startsWith("text/html"));
        assertEquals(" x=z; ",results.get(i++));
    }

    @Test
    public void testHostPort() throws Exception
    {
        final ArrayList<String> results = new ArrayList<String>();
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                results.add(request.getRequestURL().toString());
                results.add(request.getRemoteAddr());
                results.add(request.getServerName());
                results.add(String.valueOf(request.getServerPort()));
                return true;
            }
        };

        results.clear();
        String response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: myhost\n"+
                "Connection: close\n"+
                "\n");
        int i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://myhost/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("myhost",results.get(i++));
        assertEquals("80",results.get(i++));


        results.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: myhost:8888\n"+
                "Connection: close\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://myhost:8888/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("myhost",results.get(i++));
        assertEquals("8888",results.get(i++));
        

        results.clear();
        response=_connector.getResponses(
                "GET http://myhost:8888/ HTTP/1.0\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://myhost:8888/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("myhost",results.get(i++));
        assertEquals("8888",results.get(i++));
        
        results.clear();
        response=_connector.getResponses(
                "GET http://myhost:8888/ HTTP/1.1\n"+
                "Host: wrong:666\n"+
                "Connection: close\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://myhost:8888/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("myhost",results.get(i++));
        assertEquals("8888",results.get(i++));


        results.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: 1.2.3.4\n"+
                "Connection: close\n"+
                "\n");
        i=0;

        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://1.2.3.4/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("1.2.3.4",results.get(i++));
        assertEquals("80",results.get(i++));


        results.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: 1.2.3.4:8888\n"+
                "Connection: close\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://1.2.3.4:8888/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("1.2.3.4",results.get(i++));
        assertEquals("8888",results.get(i++));


        results.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: [::1]\n"+
                "Connection: close\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://[::1]/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("::1",results.get(i++));
        assertEquals("80",results.get(i++));


        results.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: [::1]:8888\n"+
                "Connection: close\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("http://[::1]:8888/",results.get(i++));
        assertEquals("0.0.0.0",results.get(i++));
        assertEquals("::1",results.get(i++));
        assertEquals("8888",results.get(i++));


        results.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: [::1]\n"+
                "x-forwarded-for: remote\n"+
                "x-forwarded-proto: https\n"+
                "Connection: close\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("https://[::1]/",results.get(i++));
        assertEquals("remote",results.get(i++));
        assertEquals("::1",results.get(i++));
        assertEquals("443",results.get(i++));


        results.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: [::1]:8888\n"+
                "Connection: close\n"+
                "x-forwarded-for: remote\n"+
                "x-forwarded-proto: https\n"+
                "\n");
        i=0;
        assertThat(response,Matchers.containsString("200 OK"));
        assertEquals("https://[::1]:8888/",results.get(i++));
        assertEquals("remote",results.get(i++));
        assertEquals("::1",results.get(i++));
        assertEquals("8888",results.get(i++));
    }

    @Test
    public void testContent() throws Exception
    {
        final AtomicInteger length=new AtomicInteger();

        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                int len=request.getContentLength();
                ServletInputStream in = request.getInputStream();
                for (int i=0;i<len;i++)
                {
                    int b=in.read();
                    if (b<0)
                        return false;
                }
                if (in.read()>0)
                    return false;

                length.set(len);
                return true;
            }
        };


        String content="";

        for (int l=0;l<1024;l++)
        {
            String request="POST / HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "Content-Type: multipart/form-data-test\r\n"+
            "Content-Length: "+l+"\r\n"+
            "Connection: close\r\n"+
            "\r\n"+
            content;
            Log.getRootLogger().debug("test l={}",l);
            String response = _connector.getResponses(request);
            Log.getRootLogger().debug(response);
            assertThat(response,Matchers.containsString(" 200 OK"));
            assertEquals(l,length.get());
            content+="x";
        }
    }

    @Test
    public void test8859EncodedForm() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                request.setCharacterEncoding(StringUtil.__ISO_8859_1);
                return "test\u00e4".equals(request.getParameter("name2"));
            }
        };


        String content="name1=test&name2=test%E4&name3=&name4=test";
        String request="POST / HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "Content-Type: "+MimeTypes.Type.FORM_ENCODED.asString()+"\r\n" +
            "Content-Length: "+content.length()+"\r\n"+
            "Connection: close\r\n"+
            "\r\n"+
            content;
        _connector.getResponses(request);
    }
    
    @Test
    public void testUTF8EncodedForm() throws Exception
    {
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                return "test\u00e4".equals(request.getParameter("name2"));
            }
        };

        String content="name1=test&name2=test%C4%A4&name3=&name4=test";
        String request="POST / HTTP/1.1\r\n"+
            "Host: whatever\r\n"+
            "Content-Type: "+MimeTypes.Type.FORM_ENCODED.asString()+"\r\n" +
            "Content-Length: "+content.length()+"\r\n"+
            "Connection: close\r\n"+
            "\r\n"+
            content;
        _connector.getResponses(request);
    }
    
    
    @Test
    public void testPartialRead() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
                    ServletException
            {
                baseRequest.setHandled(true);
                Reader reader=request.getReader();
                byte[] b=("read="+reader.read()+"\n").getBytes(StandardCharsets.UTF_8);
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
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException
            {
                baseRequest.setHandled(true);
                Reader reader=request.getReader();
                String in = IO.toString(reader);
                String param = request.getParameter("param");

                byte[] b=("read='"+in+"' param="+param+"\n").getBytes(StandardCharsets.UTF_8);
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
    public void testSessionAfterRedirect() throws Exception
    { 
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException
            {
                baseRequest.setHandled(true);
                response.sendRedirect("/foo");
                try
                {
                    request.getSession(true);
                    fail("Session should not be created after response committed");
                }
                catch (IllegalStateException e)
                {
                    //expected
                }
                catch (Exception e)
                {
                    fail("Session creation after response commit should throw IllegalStateException");
                }
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();
        String response=_connector.getResponses("GET / HTTP/1.1\n"+
                                                "Host: myhost\n"+
                                                "Connection: close\n"+
                                                "\n");
    }

    @Test
    public void testPartialInput() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException
            {
                baseRequest.setHandled(true);
                InputStream in=request.getInputStream();
                byte[] b=("read="+in.read()+"\n").getBytes(StandardCharsets.UTF_8);
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
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException
            {
                response.getOutputStream().println("Hello World");
                return true;
            }
        };

        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "\n",
                    200, TimeUnit.MILLISECONDS
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
                    "\n",
                    200, TimeUnit.MILLISECONDS
                    );
        assertTrue(response.indexOf("200")>0);
        assertTrue(response.indexOf("Connection: keep-alive")>0);
        assertTrue(response.indexOf("Hello World")>0);

        _handler._checker = new RequestTester()
        {
            @Override
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
                    "\n",
                    200, TimeUnit.MILLISECONDS
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
        assertThat(response,Matchers.containsString("200 OK"));
        assertThat(response,Matchers.containsString("Connection: close"));
        assertThat(response,Matchers.containsString("Hello World"));
    }

    @Test
    public void testCookies() throws Exception
    {
        final ArrayList<Cookie> cookies = new ArrayList<Cookie>();

        _handler._checker = new RequestTester()
        {
            @Override
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
                    "Connection: close\n"+
                    "\n"
                    );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(0,cookies.size());


        cookies.clear();
        response=_connector.getResponses(
                    "GET / HTTP/1.1\n"+
                    "Host: whatever\n"+
                    "Cookie: name=quoted=\\\"value\\\"\n" +
                    "Connection: close\n"+
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
                "Connection: close\n"+
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
                "Connection: close\n"+
                "\n"
        );
        assertThat(response,startsWith("HTTP/1.1 200 OK"));
        assertThat(response.substring(15),containsString("HTTP/1.1 200 OK"));
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
                "Connection: close\n"+
                "\n"
        );
        assertThat(response,startsWith("HTTP/1.1 200 OK"));
        assertThat(response.substring(15),containsString("HTTP/1.1 200 OK"));
        assertEquals(4,cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        assertNotSame(cookies.get(0), cookies.get(2));
        assertNotSame(cookies.get(1), cookies.get(3));

        cookies.clear();
//NOTE: the javax.servlet.http.Cookie class sets the system property org.glassfish.web.rfc2109_cookie_names_enforced
//to TRUE by default, and rejects all cookie names containing punctuation.Therefore this test cannot use "name2".
        response=_connector.getResponses(
                "POST / HTTP/1.1\r\n"+
                "Host: whatever\r\n"+
                "Cookie: name0=value0; name1 = value1 ; \"name2\"  =  \"\\\"value2\\\"\"  \n" +
                "Cookie: $Version=2; name3=value3=value3;$path=/path;$domain=acme.com;$port=8080; name4=; name5 =  ; name6\n" +
                "Cookie: name7=value7;\n" +
                "Connection: close\r\n"+
        "\r\n");

        assertEquals("name0", cookies.get(0).getName());
        assertEquals("value0", cookies.get(0).getValue());
        assertEquals("name1", cookies.get(1).getName());
        assertEquals("value1", cookies.get(1).getValue());
        assertEquals("name2", cookies.get(2).getName());
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

        cookies.clear();
        response=_connector.getResponses(
                "GET /other HTTP/1.1\n"+
                        "Host: whatever\n"+
                        "Other: header\n"+
                        "Cookie: __utmz=14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html\n"+
                        "Connection: close\n"+
                        "\n"
                );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1,cookies.size());
        assertEquals("__utmz", cookies.get(0).getName());
        assertEquals("14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html", cookies.get(0).getValue());

    }

    @Test
    public void testCookieLeak() throws Exception
    {
        final String[] cookie=new String[10];

        _handler._checker = new RequestTester()
        {
            @Override
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
        ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(true);
        LOG.info("Expecting maxFormKeys limit and Closing HttpParser exceptions...");
        _server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize",-1);
        _server.setAttribute("org.eclipse.jetty.server.Request.maxFormKeys",1000);


        StringBuilder buf = new StringBuilder(4000000);
        buf.append("a=b");

        // The evil keys file is not distributed - as it is dangerous
        File evil_keys = new File("/tmp/keys_mapping_to_zero_2m");
        if (evil_keys.exists())
        {
            LOG.info("Using real evil keys!");
            try (BufferedReader in = new BufferedReader(new FileReader(evil_keys)))
            {
                String key=null;
                while((key=in.readLine())!=null)
                    buf.append("&").append(key).append("=").append("x");
            }
        }
        else
        {
            // we will just create a lot of keys and make sure the limit is applied
            for (int i=0;i<2000;i++)
                buf.append("&").append("K").append(i).append("=").append("x");
        }
        buf.append("&c=d");


        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request,HttpServletResponse response)
            {
                return "b".equals(request.getParameter("a")) && request.getParameter("c")==null;
            }
        };

        String request="POST / HTTP/1.1\r\n"+
        "Host: whatever\r\n"+
        "Content-Type: "+MimeTypes.Type.FORM_ENCODED.asString()+"\r\n"+
        "Content-Length: "+buf.length()+"\r\n"+
        "Connection: close\r\n"+
        "\r\n"+
        buf;

        try
        {
            long start=System.currentTimeMillis();
            String response = _connector.getResponses(request);
            assertTrue(response.contains("Form too many keys"));
            long now=System.currentTimeMillis();
            assertTrue((now-start)<5000);
        }
        finally
        {
            ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(false);
        }
    }

    @Test(expected = UnsupportedEncodingException.class)
    public void testNotSupportedCharacterEncoding() throws UnsupportedEncodingException
    {
        Request request = new Request(null, null);
        request.setCharacterEncoding("doesNotExist");
    }

    interface RequestTester
    {
        boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException;
    }

    private class RequestHandler extends AbstractHandler
    {
        private RequestTester _checker;
        private String _content;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);

            if (request.getContentLength()>0
                    && !MimeTypes.Type.FORM_ENCODED.asString().equals(request.getContentType())
                    && !request.getContentType().startsWith("multipart/form-data"))
                _content=IO.toString(request.getInputStream());

            if (_checker!=null && _checker.check(request,response))
                response.setStatus(200);
            else
                response.sendError(500);
        }
    }

    private class MultiPartRequestHandler extends AbstractHandler
    {
        File tmpDir;

        public MultiPartRequestHandler(File tmpDir)
        {
            this.tmpDir = tmpDir;
        }


        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);
            try
            {

                MultipartConfigElement mpce = new MultipartConfigElement(tmpDir.getAbsolutePath(),-1, -1, 2);
                request.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, mpce);

                String field1 = request.getParameter("field1");
                assertNotNull(field1);

                Part foo = request.getPart("stuff");
                assertNotNull(foo);
                assertTrue(foo.getSize() > 0);
                response.setStatus(200);
            }
            catch (IllegalStateException e)
            {
                //expected exception because no multipart config is set up
                assertTrue(e.getMessage().startsWith("No multipart config"));
                response.setStatus(200);
            }
            catch (Exception e)
            {
                response.sendError(500);
            }
        }
    }
}
