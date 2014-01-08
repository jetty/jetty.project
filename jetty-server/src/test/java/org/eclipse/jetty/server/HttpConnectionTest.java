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

/*
 * Created on 9/01/2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 */
public class HttpConnectionTest
{
    private Server server;
    private LocalConnector connector;

    @Before
    public void init() throws Exception
    {
        server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setRequestHeaderSize(1024);
        http.getHttpConfiguration().setResponseHeaderSize(1024);
        
        connector = new LocalConnector(server,http,null);
        connector.setIdleTimeout(500);
        server.addConnector(connector);
        server.setHandler(new DumpHandler());
        server.addBean(new ErrorHandler());
        server.start();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testFragmentedChunk() throws Exception
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
                                           "Connection: close\n"+
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
                                           "Connection: close\n"+
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
            if(response != null)
                System.err.println(response);
            throw e;
        }
    }

    @Test
    public void testNoPath() throws Exception
    {
        String response=connector.getResponses("GET http://localhost:80 HTTP/1.1\n"+
                "Host: localhost:80\n"+
                "Connection: close\n"+
                "\n");

        int offset=0;
        offset = checkContains(response,offset,"HTTP/1.1 200");
        checkContains(response,offset,"pathInfo=/");
    }

    @Test
    public void testBadNoPath() throws Exception
    {
        String response=connector.getResponses("GET http://localhost:80/../cheat HTTP/1.1\n"+
                "Host: localhost:80\n"+
                "\n");
        int offset=0;
        offset = checkContains(response,offset,"HTTP/1.1 400");
    }

    @Test
    public void testOKPathDotDotPath() throws Exception
    {
        String response=connector.getResponses("GET /ooops/../path HTTP/1.0\nHost: localhost:80\n\n");
        checkContains(response,0,"HTTP/1.1 200 OK");
        checkContains(response,0,"pathInfo=/path");
    }
    
    @Test
    public void testBadPathDotDotPath() throws Exception
    {
        String response=connector.getResponses("GET /ooops/../../path HTTP/1.0\nHost: localhost:80\n\n");
        checkContains(response,0,"HTTP/1.1 400 Bad Request");
    }
    
    @Test
    public void testOKPathEncodedDotDotPath() throws Exception
    {
        String response=connector.getResponses("GET /ooops/%2e%2e/path HTTP/1.0\nHost: localhost:80\n\n");
        checkContains(response,0,"HTTP/1.1 200 OK");
        checkContains(response,0,"pathInfo=/path");
    }
    
    @Test
    public void testBadPathEncodedDotDotPath() throws Exception
    {
        String response=connector.getResponses("GET /ooops/%2e%2e/%2e%2e/path HTTP/1.0\nHost: localhost:80\n\n");
        checkContains(response,0,"HTTP/1.1 400 Bad Request");
    }
    
    @Test
    public void testBadDotDotPath() throws Exception
    {
        String response=connector.getResponses("GET ../path HTTP/1.0\nHost: localhost:80\n\n");
        checkContains(response,0,"HTTP/1.1 400 Bad Request");
    }
    
    @Test
    public void testBadSlashDotDotPath() throws Exception
    {
        String response=connector.getResponses("GET /../path HTTP/1.0\nHost: localhost:80\n\n");
        checkContains(response,0,"HTTP/1.1 400 Bad Request");
    }

    @Test
    public void testEncodedBadDotDotPath() throws Exception
    {
        String response=connector.getResponses("GET %2e%2e/path HTTP/1.0\nHost: localhost:80\n\n");
        checkContains(response,0,"HTTP/1.1 400 Bad Request");
    }

    @Test
    public void testSimple() throws Exception
    {
        String response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                "Host: localhost\n"+
                "Connection: close\n"+
                "\n");

        int offset=0;
        offset = checkContains(response,offset,"HTTP/1.1 200");
        checkContains(response,offset,"/R1");
    }

    @Test
    public void testEmptyChunk() throws Exception
    {
        String response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                "Host: localhost\n"+
                "Transfer-Encoding: chunked\n"+
                "Content-Type: text/plain\n"+
                "Connection: close\n"+
                "\015\012"+
        "0\015\012\015\012");

        int offset=0;
        offset = checkContains(response,offset,"HTTP/1.1 200");
        checkContains(response,offset,"/R1");
    }

    @Test
    public void testHead() throws Exception
    {
        String responsePOST=connector.getResponses("POST /R1 HTTP/1.1\015\012"+
                "Host: localhost\015\012"+
                "Connection: close\015\012"+
                "\015\012");
        
        String responseHEAD=connector.getResponses("HEAD /R1 HTTP/1.1\015\012"+
            "Host: localhost\015\012"+
            "Connection: close\015\012"+
            "\015\012");

        assertThat(responsePOST,startsWith(responseHEAD.substring(0,responseHEAD.length()-2)));
        assertThat(responsePOST.length(),greaterThan(responseHEAD.length()));

        responsePOST=connector.getResponses("POST /R1 HTTP/1.1\015\012"+
                "Host: localhost\015\012"+
                "Connection: close\015\012"+
                "\015\012");

        assertThat(responsePOST,startsWith(responseHEAD.substring(0,responseHEAD.length()-2)));
        assertThat(responsePOST.length(),greaterThan(responseHEAD.length()));
    }

    @Test
    public void testBadHostPort() throws Exception
    {
        Log.getLogger(HttpParser.class).info("badMessage: Number formate exception expected ...");
        String response;

        response=connector.getResponses("GET http://localhost:EXPECTED_NUMBER_FORMAT_EXCEPTION/ HTTP/1.1\n"+
            "Host: localhost\n"+
            "Connection: close\015\012"+
            "\015\012");
        checkContains(response,0,"HTTP/1.1 400");
    }

    @Test
    public void testBadURIencoding() throws Exception
    {
        Log.getLogger(HttpParser.class).info("badMessage: bad encoding expected ...");
        String response;

        response=connector.getResponses("GET /bad/encoding%1 HTTP/1.1\n"+
            "Host: localhost\n"+
            "Connection: close\n"+
            "\015\012");
        checkContains(response,0,"HTTP/1.1 400");
    }

    @Test
    public void testBadUTF8FallsbackTo8859() throws Exception
    {
        Log.getLogger(HttpParser.class).info("badMessage: bad encoding expected ...");
        String response;

        response=connector.getResponses("GET /foo/bar%c0%00 HTTP/1.1\n"+
            "Host: localhost\n"+
            "Connection: close\n"+
            "\015\012");
        checkContains(response,0,"HTTP/1.1 200"); //now fallback to iso-8859-1

        response=connector.getResponses("GET /bad/utf8%c1 HTTP/1.1\n"+
            "Host: localhost\n"+
            "Connection: close\n"+
            "\015\012");
        checkContains(response,0,"HTTP/1.1 200"); //now fallback to iso-8859-1
    }

    @Test
    public void testAutoFlush() throws Exception
    {
        String response=null;
        int offset=0;

        offset=0;
        response=connector.getResponses("GET /R1 HTTP/1.1\n"+
            "Host: localhost\n"+
            "Transfer-Encoding: chunked\n"+
            "Content-Type: text/plain\n"+
            "Connection: close\n"+
            "\015\012"+
            "5;\015\012"+
            "12345\015\012"+
            "0;\015\012\015\012");
        offset = checkContains(response,offset,"HTTP/1.1 200");
        checkNotContained(response,offset,"IgnoreMe");
        offset = checkContains(response,offset,"/R1");
        offset = checkContains(response,offset,"12345");
    }

    @Test
    public void testCharset() throws Exception
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
                                           "Connection: close\n"+
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
                                           "Connection: close\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"encoding=ISO-8859-1");
            offset = checkContains(response,offset,"/R1");
            offset = checkContains(response,offset,"12345");

            offset=0;
            response=connector.getResponses("GET /R1 HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=unknown\n"+
                                           "Connection: close\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"encoding=unknown");
            offset = checkContains(response,offset,"/R1");
            offset = checkContains(response,offset,"UnsupportedEncodingException");


        }
        catch(Exception e)
        {
            if(response != null)
                System.err.println(response);
            throw e;
        }
    }

    @Test
    public void testUnconsumed() throws Exception
    {
        String response=null;
        String requests=null;
        int offset=0;

        offset=0;
        requests=
        "GET /R1?read=4 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Transfer-Encoding: chunked\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "\015\012"+
        "5;\015\012"+
        "12345\015\012"+
        "5;\015\012"+
        "67890\015\012"+
        "0;\015\012\015\012"+
        "GET /R2 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "Content-Length: 10\n"+
        "Connection: close\n"+
        "\n"+
        "abcdefghij\n";

        response=connector.getResponses(requests);
        
        offset = checkContains(response,offset,"HTTP/1.1 200");
        offset = checkContains(response,offset,"pathInfo=/R1");
        offset = checkContains(response,offset,"1234");
        checkNotContained(response,offset,"56789");
        offset = checkContains(response,offset,"HTTP/1.1 200");
        offset = checkContains(response,offset,"pathInfo=/R2");
        offset = checkContains(response,offset,"encoding=UTF-8");
        offset = checkContains(response,offset,"abcdefghij");
    }

    @Test
    public void testUnconsumedTimeout() throws Exception
    {
        String response=null;
        String requests=null;
        int offset=0;

        offset=0;
        requests=
        "GET /R1?read=4 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Transfer-Encoding: chunked\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "\015\012"+
        "5;\015\012"+
        "12345\015\012";

        long start=System.currentTimeMillis();
        response=connector.getResponses(requests,2000,TimeUnit.MILLISECONDS);
        if ((System.currentTimeMillis()-start)>=2000)
            Assert.fail();
        
        offset = checkContains(response,offset,"HTTP/1.1 200");
        offset = checkContains(response,offset,"pathInfo=/R1");
        offset = checkContains(response,offset,"1234");
        checkNotContained(response,offset,"56789");
    }
    
    @Test
    public void testUnconsumedErrorRead() throws Exception
    {
        String response=null;
        String requests=null;
        int offset=0;

        offset=0;
        requests=
        "GET /R1?read=1&error=499 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Transfer-Encoding: chunked\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "\015\012"+
        "5;\015\012"+
        "12345\015\012"+
        "5;\015\012"+
        "67890\015\012"+
        "0;\015\012\015\012"+
        "GET /R2 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "Content-Length: 10\n"+
        "Connection: close\n"+
        "\n"+
        "abcdefghij\n";

        response=connector.getResponses(requests);

        offset = checkContains(response,offset,"HTTP/1.1 499");
        offset = checkContains(response,offset,"HTTP/1.1 200");
        offset = checkContains(response,offset,"/R2");
        offset = checkContains(response,offset,"encoding=UTF-8");
        offset = checkContains(response,offset,"abcdefghij");
    }
    
    @Test
    public void testUnconsumedErrorStream() throws Exception
    {
        String response=null;
        String requests=null;
        int offset=0;

        offset=0;
        requests=
        "GET /R1?error=599 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Transfer-Encoding: chunked\n"+
        "Content-Type: application/data; charset=utf-8\n"+
        "\015\012"+
        "5;\015\012"+
        "12345\015\012"+
        "5;\015\012"+
        "67890\015\012"+
        "0;\015\012\015\012"+
        "GET /R2 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "Content-Length: 10\n"+
        "Connection: close\n"+
        "\n"+
        "abcdefghij\n";

        response=connector.getResponses(requests);

        offset = checkContains(response,offset,"HTTP/1.1 599");
        offset = checkContains(response,offset,"HTTP/1.1 200");
        offset = checkContains(response,offset,"/R2");
        offset = checkContains(response,offset,"encoding=UTF-8");
        offset = checkContains(response,offset,"abcdefghij");
    }

    @Test
    public void testUnconsumedException() throws Exception
    {
        String response=null;
        String requests=null;
        int offset=0;

        offset=0;
        requests="GET /R1?read=1&ISE=true HTTP/1.1\n"+
        "Host: localhost\n"+
        "Transfer-Encoding: chunked\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "\015\012"+
        "5;\015\012"+
        "12345\015\012"+
        "5;\015\012"+
        "67890\015\012"+
        "0;\015\012\015\012"+
        "GET /R2 HTTP/1.1\n"+
        "Host: localhost\n"+
        "Content-Type: text/plain; charset=utf-8\n"+
        "Content-Length: 10\n"+
        "\n"+
        "abcdefghij\n";

        Logger logger = Log.getLogger(HttpChannel.class);
        try
        {
            logger.info("EXPECTING: java.lang.IllegalStateException...");
            ((StdErrLog)logger).setHideStacks(true);
            response=connector.getResponses(requests);
            offset = checkContains(response,offset,"HTTP/1.1 500");
            offset = checkContains(response,offset,"Connection: close");
            checkNotContained(response,offset,"HTTP/1.1 200");
        }
        finally
        {
            ((StdErrLog)logger).setHideStacks(false);
        }
    }

    @Test
    public void testConnection() throws Exception
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
            checkContains(response,offset,"Connection: close");
        }
        catch (Exception e)
        {
            if(response != null)
                System.err.println(response);
            throw e;
        }
    }

    /**
     * Creates a request header over 1k in size, by creating a single header entry with an huge value.
     */
    @Test
    public void testOversizedBuffer() throws Exception
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
            checkContains(response, offset, "HTTP/1.1 413");
        }
        catch(Exception e)
        {
            if(response != null)
                System.err.println(response);
            throw e;
        }
    }

    /**
     * Creates a request header with over 1000 entries.
     */
    @Test
    public void testExcessiveHeader() throws Exception
    {
        String response = null;
        int offset = 0;

        StringBuilder request = new StringBuilder();
        request.append("GET / HTTP/1.1\n");
        request.append("Host: localhost\n");
        request.append("Cookie: thisisastring\n");
        for(int i=0; i<1000; i++) {
            request.append(String.format("X-Header-%04d: %08x\n", i, i));
        }
        request.append("\015\012");

        response = connector.getResponses(request.toString());
        offset = checkContains(response, offset, "HTTP/1.1 413");
        checkContains(response, offset, "<h1>Bad Message 413</h1><pre>reason: Request Entity Too Large</pre>");
    }

    @Test
    public void testOversizedResponse() throws Exception
    {
        String str = "thisisastringthatshouldreachover1kbytes-";
        for (int i=0;i<500;i++)
            str+="xxxxxxxxxxxx";
        final String longstr = str;
        final CountDownLatch checkError = new CountDownLatch(1);
        String response = null;
        server.stop();
        server.setHandler(new DumpHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader(HttpHeader.CONTENT_TYPE.toString(),MimeTypes.Type.TEXT_HTML.toString());
                response.setHeader("LongStr", longstr);
                PrintWriter writer = response.getWriter();
                writer.write("<html><h1>FOO</h1></html>");
                writer.flush();
                if (writer.checkError())
                    checkError.countDown();
                response.flushBuffer();
            }
        });
        server.start();

        try
        {
            ((StdErrLog)Log.getLogger(HttpChannel.class)).info("Excpect IOException: Response header too large...");
            ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(true);
            int offset = 0;

            response = connector.getResponses("GET / HTTP/1.1\n"+
                "Host: localhost\n" +
                "\015\012"
             );

            checkContains(response, offset, "HTTP/1.1 500");
            assertTrue(checkError.await(1,TimeUnit.SECONDS));
        }
        catch(Exception e)
        {
            if(response != null)
                System.err.println(response);
            throw e;
        }
        finally
        {

            ((StdErrLog)Log.getLogger(HttpChannel.class)).setHideStacks(false);
        }
    }

    @Test
    public void testAsterisk() throws Exception
    {
        String response = null;

        try
        {
            ((StdErrLog)HttpParser.LOG).setHideStacks(true);
            int offset=0;

            response=connector.getResponses("OPTIONS * HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "Connection: close\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 200");
            offset = checkContains(response,offset,"Allow: GET,POST,HEAD");

            offset=0;
            response=connector.getResponses("GET * HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "Connection: close\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 400");

            offset=0;
            response=connector.getResponses("GET ** HTTP/1.1\n"+
                                           "Host: localhost\n"+
                                           "Transfer-Encoding: chunked\n"+
                                           "Content-Type: text/plain; charset=utf-8\n"+
                                           "Connection: close\n"+
                                           "\015\012"+
                                           "5;\015\012"+
                                           "12345\015\012"+
                                           "0;\015\012\015\012");
            offset = checkContains(response,offset,"HTTP/1.1 400 Bad Request");
        }
        catch (Exception e)
        {
            if(response != null)
                System.err.println(response);
            throw e;
        }
        finally
        {
            ((StdErrLog)HttpParser.LOG).setHideStacks(false);
        }

    }

    @Test
    public void testCONNECT() throws Exception
    {
        String response = null;

        try
        {
            int offset=0;

            response=connector.getResponses("CONNECT www.webtide.com:8080 HTTP/1.1\n"+
                                           "Host: myproxy:8888\015\012"+
                                           "\015\012",200,TimeUnit.MILLISECONDS);
            checkContains(response,offset,"HTTP/1.1 200");

        }
        catch (Exception e)
        {
            if(response != null)
                System.err.println(response);
            throw e;
        }

    }

    private int checkContains(String s,int offset,String c)
    {
        Assert.assertThat(s.substring(offset),Matchers.containsString(c));
        return s.indexOf(c,offset);
    }

    private void checkNotContained(String s,int offset,String c)
    {
        Assert.assertThat(s.substring(offset),Matchers.not(Matchers.containsString(c)));
    }
}


