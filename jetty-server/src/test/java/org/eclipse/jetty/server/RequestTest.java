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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;

/**
 *
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class RequestTest extends TestCase
{
    Server _server = new Server();
    LocalConnector _connector = new LocalConnector();
    RequestHandler _handler = new RequestHandler();

    {
        _connector.setHeaderBufferSize(512);
        _connector.setRequestBufferSize(1024);
        _connector.setResponseBufferSize(2048);
    }

    public RequestTest(String arg0)
    {
        super(arg0);
        _server.setConnectors(new Connector[]{_connector});

    }

    public static void main(String[] args)
    {
        junit.textui.TestRunner.run(RequestTest.class);
    }

    /*
     * @see TestCase#setUp()
     */
    protected void setUp() throws Exception
    {
        super.setUp();

        _server.setHandler(_handler);
        _server.start();
    }

    /*
     * @see TestCase#tearDown()
     */
    protected void tearDown() throws Exception
    {
        super.tearDown();
        _server.stop();
    }


    public void testContentTypeEncoding()
    	throws Exception
    {
        final ArrayList results = new ArrayList();
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

        assertTrue(((String)results.get(i++)).startsWith("text/html"));
        assertEquals(" x=z; ",results.get(i++));


    }



    public void testContent()
        throws Exception
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

    public void testPartialRead()
        throws Exception
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
        System.err.println("response="+responses);
        
        int index=responses.indexOf("read="+(int)'0');
        assertTrue(index>0);
        
        index=responses.indexOf("read="+(int)'A',index+7);
        assertTrue(index>0);
        
    }

    public void testPartialInput()
    throws Exception
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
        System.err.println("response="+responses);

        int index=responses.indexOf("read="+(int)'0');
        assertTrue(index>0);

        index=responses.indexOf("read="+(int)'A',index+7);
        assertTrue(index>0);

    }

    public void testConnectionClose()
        throws Exception
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
        assertTrue(response.indexOf("Connection: close")>0);
        assertTrue(response.indexOf("Hello World")>0);

        response=_connector.getResponses(
                    "GET / HTTP/1.0\n"+
                    "Host: whatever\n"+
                    "Connection: Other, keep-alive\n"+
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


    public void testCookies() throws Exception
    {
        final ArrayList cookies = new ArrayList();

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
        assertEquals("name",((Cookie)cookies.get(0)).getName());
        assertEquals("quoted=\\\"value\\\"",((Cookie)cookies.get(0)).getValue());

        cookies.clear();
        response=_connector.getResponses(
                "GET / HTTP/1.1\n"+
                "Host: whatever\n"+
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(2,cookies.size());
        assertEquals("name",((Cookie)cookies.get(0)).getName());
        assertEquals("value",((Cookie)cookies.get(0)).getValue());
        assertEquals("other",((Cookie)cookies.get(1)).getName());
        assertEquals("quoted=;value",((Cookie)cookies.get(1)).getValue());


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
        assertEquals("name",((Cookie)cookies.get(0)).getName());
        assertEquals("value",((Cookie)cookies.get(0)).getValue());
        assertEquals("other",((Cookie)cookies.get(1)).getName());
        assertEquals("quoted=;value",((Cookie)cookies.get(1)).getValue());

        assertTrue((Cookie)cookies.get(0)==(Cookie)cookies.get(2));
        assertTrue((Cookie)cookies.get(1)==(Cookie)cookies.get(3));


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
        assertEquals("name",((Cookie)cookies.get(0)).getName());
        assertEquals("value",((Cookie)cookies.get(0)).getValue());
        assertEquals("other",((Cookie)cookies.get(1)).getName());
        assertEquals("quoted=;value",((Cookie)cookies.get(1)).getValue());

        assertTrue((Cookie)cookies.get(0)!=(Cookie)cookies.get(2));
        assertTrue((Cookie)cookies.get(1)!=(Cookie)cookies.get(3));

        cookies.clear();
        response=_connector.getResponses(
                "POST / HTTP/1.1\r\n"+
                "Host: whatever\r\n"+
                "Cookie: name0=value0; name1 = value1 ; \"\\\"name2\\\"\"  =  \"\\\"value2\\\"\"  \n" +
                "Cookie: $Version=2; name3=value3=value3;$path=/path;$domain=acme.com;$port=8080, name4=; name5 =  ; name6\n" +
                "Cookie: name7=value7;\n" +
                "Connection: close\r\n"+
        "\r\n");

        assertEquals("name0",((Cookie)cookies.get(0)).getName());
        assertEquals("value0",((Cookie)cookies.get(0)).getValue());
        assertEquals("name1",((Cookie)cookies.get(1)).getName());
        assertEquals("value1",((Cookie)cookies.get(1)).getValue());
        assertEquals("\"name2\"",((Cookie)cookies.get(2)).getName());
        assertEquals("\"value2\"",((Cookie)cookies.get(2)).getValue());
        assertEquals("name3",((Cookie)cookies.get(3)).getName());
        assertEquals("value3=value3",((Cookie)cookies.get(3)).getValue());
        assertEquals(2,((Cookie)cookies.get(3)).getVersion());
        assertEquals("/path",((Cookie)cookies.get(3)).getPath());
        assertEquals("acme.com",((Cookie)cookies.get(3)).getDomain());
        assertEquals("$port=8080",((Cookie)cookies.get(3)).getComment());
        assertEquals("name4",((Cookie)cookies.get(4)).getName());
        assertEquals("",((Cookie)cookies.get(4)).getValue());
        assertEquals("name5",((Cookie)cookies.get(5)).getName());
        assertEquals("",((Cookie)cookies.get(5)).getValue());
        assertEquals("name6",((Cookie)cookies.get(6)).getName());
        assertEquals("",((Cookie)cookies.get(6)).getValue());
        assertEquals("name7",((Cookie)cookies.get(7)).getName());
        assertEquals("value7",((Cookie)cookies.get(7)).getValue());

    }

    public void testCookieLeak()
        throws Exception
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




    interface RequestTester
    {
        boolean check(HttpServletRequest request,HttpServletResponse response) throws IOException;
    }

    class RequestHandler extends AbstractHandler
    {
        RequestTester _checker;
        String _content;

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);

            if (request.getContentLength()>0)
                _content=IO.toString(request.getInputStream());

            if (_checker!=null && _checker.check(request,response))
                response.setStatus(200);
            else
                response.sendError(500);


        }
    }

}
