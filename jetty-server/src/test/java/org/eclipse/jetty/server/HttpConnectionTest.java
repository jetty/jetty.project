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

/*
 * Created on 9/01/2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.log.Log;

/**
 *
 *
 */
public class HttpConnectionTest extends TestCase
{
    Server server = new Server();
    LocalConnector connector = new LocalConnector();

    /**
     * Constructor
     * @param arg0
     */
    public HttpConnectionTest(String arg0)
    {
        super(arg0);
        server.setConnectors(new Connector[]{connector});
        server.setHandler(new DumpHandler());
    }

    /*
     * @see TestCase#setUp()
     */
    protected void setUp() throws Exception
    {
        super.setUp();
	connector.setHeaderBufferSize(1024);
        server.start();
    }

    /*
     * @see TestCase#tearDown()
     */
    protected void tearDown() throws Exception
    {
        super.tearDown();
        server.stop();
    }



    /* --------------------------------------------------------------- */
    public void testFragmentedChunk()
    {
        String response=null;
        try
        {
            int offset=0;

            // Chunk last
            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"/R1");
            offset = checkContains(response,offset,"12345");

            offset = 0;
            response=connector.getResponses("GET /R2 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "ABCDE\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"/R2");
            offset = checkContains(response,offset,"ABCDE");
        }
        catch(Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
            if (response!=null)
                System.err.println(response);
        }
    }

    /* --------------------------------------------------------------- */
    public void testEmpty() throws Exception
    {
        String response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                "Host: localhost\n"+
                "Transfer-Encoding: chunked\n"+
                "Content-Type: text/plain\n"+
                "\015\012"+
        "0\015\012\015\012");

        int offset=0;
        offset = checkContains(response,offset,"HTTP/1.1 200");
        offset = checkContains(response,offset,"/R1");
    }

    /* --------------------------------------------------------------- */
    public void testBad() throws Exception
    {
        String response=connector.getResponses("GET & HTTP/1.1\n"+
                "Host: localhost\n"+
                "\015\012");
        checkContains(response,0,"HTTP/1.1 400");

        response=connector.getResponses("GET http://localhost:WRONG/ HTTP/1.1\n"+
                "Host: localhost\n"+
                "\015\012");
        checkContains(response,0,"HTTP/1.1 400");

        response=connector.getResponses("GET /foo/bar%1 HTTP/1.1\n"+
                "Host: localhost\n"+
                "\015\012");
        checkContains(response,0,"HTTP/1.1 400");

        response=connector.getResponses("GET /foo/bar%c0%00 HTTP/1.1\n"+
                "Host: localhost\n"+
                "\015\012");
        checkContains(response,0,"HTTP/1.1 400");

        response=connector.getResponses("GET /foo/bar%c1 HTTP/1.1\n"+
                "Host: localhost\n"+
                "\015\012");
        checkContains(response,0,"HTTP/1.1 400");

    }

    /* --------------------------------------------------------------- */
    public void testAutoFlush() throws Exception
    {
        String response=null;
            int offset=0;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            checkNotContained(response,offset,"IgnoreMe");
            offset = checkContains(response,offset,"/R1");
            offset = checkContains(response,offset,"12345");
    }

    /* --------------------------------------------------------------- */
    public void testCharset()
    {

        String response=null;
        try
        {
            int offset=0;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"/R1");
            offset = checkContains(response,offset,"encoding=UTF-8");
            offset = checkContains(response,offset,"12345");

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset =  iso-8859-1 ; other=value\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"encoding=iso-8859-1");
            offset = checkContains(response,offset,"/R1");
            offset = checkContains(response,offset,"12345");

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=unknown\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"encoding=unknown");
            offset = checkContains(response,offset,"/R1");
            offset = checkContains(response,offset,"12345");


        }
        catch(Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
            if (response!=null)
                System.err.println(response);
        }
    }


    public void testConnection ()
    {
        String response=null;
        try
        {
            int offset=0;

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Connection: TE, close\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"Connection: close");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
            if (response!=null)
                 System.err.println(response);
        }
    }

    public void testOversizedBuffer()
    {
        String response = null;
        try
        {
            int offset = 0;
            String cookie = "thisisastringthatshouldreachover1kbytes";
            for (int i=0;i<100;i++)
                cookie+="xxxxxxxxxxxx";
            response = connector.getResponses("GET / HTTP/1.1\n"+
                "Host: localhost\n" +
                "Cookie: "+cookie+"\n"+
                "\015\012"
             );
            offset = checkContains(response, offset, "HTTP/1.1 413");
        }
        catch(Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
            if(response != null)
                System.err.println(response);

        }
    }
    
    
    public void testOversizedResponse ()
    throws Exception
    {  
        String str = "thisisastringthatshouldreachover1kbytes";
        for (int i=0;i<400;i++)
            str+="xxxxxxxxxxxx";
        final String longstr = str;
        String response = null;
        server.stop();
        server.setHandler(new DumpHandler()
        {  
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    response.setHeader(HttpHeaders.CONTENT_TYPE,MimeTypes.TEXT_HTML);
                    response.setHeader("LongStr", longstr);
                    PrintWriter writer = response.getWriter();
                    writer.write("<html><h1>FOO</h1></html>");  
                    writer.flush();
                    writer.close();
                    throw new RuntimeException("SHOULD NOT GET HERE");
                }
                catch(ArrayIndexOutOfBoundsException e)
                {
                    Log.debug(e);
                    Log.info("correctly ignored "+e);
                }
            }
        });
        server.start();
        
        try 
        {
            int offset = 0;
          
            response = connector.getResponses("GET / HTTP/1.1\n"+
                "Host: localhost\n" +
                "\015\012"
             );
          
            offset = checkContains(response, offset, "HTTP/1.1 500");
        } 
        catch(Exception e)
        {
            e.printStackTrace();
            if(response != null)
                System.err.println(response);
            fail("Exception");      
        }
    }
    

    public void testAsterisk()
    {
        String response = null;

        try
        {
            int offset=0;

            response=connector.getResponses("OPTIONS * HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"*");

            // to prevent the DumpHandler from picking this up and returning 200 OK
            server.stop();
            server.setHandler(null);
            server.start();
            offset=0;
            response=connector.getResponses("GET * HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 404 Not Found");

            offset=0;
            response=connector.getResponses("GET ** HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 400 Bad Request");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
            if (response!=null)
                 System.err.println(response);
        }

    }

    private int checkContains(String s,int offset,String c)
    {
        int o=s.indexOf(c,offset);
        if (o<offset)
        {
            System.err.println("FAILED");
            System.err.println("'"+c+"' not in:");
            System.err.println(s.substring(offset));
            System.err.flush();
            System.out.println("--\n"+s);
            System.out.flush();
            assertTrue(false);
        }
        return o;
    }

    private void checkNotContained(String s,int offset,String c)
    {
        int o=s.indexOf(c,offset);
        if (o>=offset)
        {
            System.err.println("FAILED");
            System.err.println("'"+c+"' IS in:");
            System.err.println(s.substring(offset));
            System.err.flush();
            System.out.println("--\n"+s);
            System.out.flush();
            assertTrue(false);
        }
    }
}


