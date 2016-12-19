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

package org.eclipse.jetty.server.handler;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.AbstractNCSARequestLog;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RequestLogTest
{
    Exchanger<String> _log;
    Server _server;
    LocalConnector _connector;
    

    @Before
    public void before() throws Exception
    {
        _log = new Exchanger<String>();
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _server.setRequestLog(new Log());
        _server.setHandler(new TestHandler());
        _server.start();
    }
    
    @After
    public void after() throws Exception
    {

        _server.stop();
    }
    
    
    @Test
    public void testNotHandled() throws Exception
    {
        _connector.getResponse("GET /foo HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo HTTP/1.0\" 404 "));
    }

    @Test
    public void testRequestLine() throws Exception
    {
        _connector.getResponse("GET /foo?data=1 HTTP/1.0\nhost: host:80\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?data=1 HTTP/1.0\" 200 "));
        
        _connector.getResponse("GET //bad/foo?data=1 HTTP/1.0\n\n");
        log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET //bad/foo?data=1 HTTP/1.0\" 200 "));
                
        _connector.getResponse("GET http://host:80/foo?data=1 HTTP/1.0\n\n");
        log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://host:80/foo?data=1 HTTP/1.0\" 200 "));   
    }
    
    @Test
    public void testHTTP10Host() throws Exception
    {
        _connector.getResponse(
            "GET /foo?name=value HTTP/1.0\n"+
            "Host: servername\n"+
            "\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testHTTP11() throws Exception
    {
        _connector.getResponse(
            "GET /foo?name=value HTTP/1.1\n"+
            "Host: servername\n"+
            "\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testAbsolute() throws Exception
    {
        _connector.getResponse(
            "GET http://hostname:8888/foo?name=value HTTP/1.1\n"+
            "Host: servername\n"+
            "\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://hostname:8888/foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testQuery() throws Exception
    {
        _connector.getResponse("GET /foo?name=value HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?name=value"));
        assertThat(log,containsString(" 200 "));
    }
    
    @Test
    public void testSmallData() throws Exception
    {
        _connector.getResponse("GET /foo?data=42 HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 200 42 "));
    }
    
    @Test
    public void testBigData() throws Exception
    {
        _connector.getResponse("GET /foo?data=102400 HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 200 102400 "));
    }
    
    @Test
    public void testStatus() throws Exception
    {
        _connector.getResponse("GET /foo?status=206 HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 206 0 "));
    }
    
    @Test
    public void testStatusData() throws Exception
    {
        _connector.getResponse("GET /foo?status=206&data=42 HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo?"));
        assertThat(log,containsString(" 206 42 "));
    }
    
    @Test
    public void testBadRequest() throws Exception
    {
        _connector.getResponse("XXXXXXXXXXXX\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 400 0 "));
    }
    
    @Test
    public void testBadCharacter() throws Exception
    {
        _connector.getResponse("METHOD /f\00o HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 400 0 "));
    }
    
    @Test
    public void testBadVersion() throws Exception
    {
        _connector.getResponse("METHOD /foo HTTP/9\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 400 0 "));
    }
    
    @Test
    public void testLongURI() throws Exception
    {
        char[] chars = new char[10000];
        Arrays.fill(chars,'o');
        String ooo = new String(chars);
        _connector.getResponse("METHOD /f"+ooo+" HTTP/1.0\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"- - -\""));
        assertThat(log,containsString(" 414 0 "));
    }
    
    @Test
    public void testLongHeader() throws Exception
    {
        char[] chars = new char[10000];
        Arrays.fill(chars,'o');
        String ooo = new String(chars);
        _connector.getResponse("METHOD /foo HTTP/1.0\name: f+"+ooo+"\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("\"METHOD /foo HTTP/1.0\""));
        assertThat(log,containsString(" 431 0 "));
    }
    
    @Test
    public void testBadRequestNoHost() throws Exception
    {
        _connector.getResponse("GET /foo HTTP/1.1\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET /foo "));
        assertThat(log,containsString(" 400 0 "));
    }

    @Test
    public void testUseragentWithout() throws Exception
    {
        _connector.getResponse("GET http://[:1]/foo HTTP/1.1\nReferer: http://other.site\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://[:1]/foo "));
        assertThat(log,containsString(" 400 0 \"http://other.site\" \"-\" - "));
    }

    @Test
    public void testUseragentWith() throws Exception
    {
        _connector.getResponse("GET http://[:1]/foo HTTP/1.1\nReferer: http://other.site\nUser-Agent: Mozilla/5.0 (test)\n\n");
        String log = _log.exchange(null,5,TimeUnit.SECONDS);
        assertThat(log,containsString("GET http://[:1]/foo "));
        assertThat(log,containsString(" 400 0 \"http://other.site\" \"Mozilla/5.0 (test)\" - "));
    }

    private class Log extends AbstractNCSARequestLog
    {
        {
            super.setExtended(true);
            super.setLogLatency(true);
            super.setLogCookies(true);
        }

        @Override
        protected boolean isEnabled()
        {
            return true;
        }

        @Override
        public void write(String requestEntry) throws IOException
        {
            try
            {
                _log.exchange(requestEntry);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }
    }
    
    private class TestHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            String q = request.getQueryString();
            if (q==null)
                return;
            
            baseRequest.setHandled(true);
            for (String action : q.split("\\&"))
            {
                String[] param = action.split("=");
                String name=param[0];
                String value=param.length>1?param[1]:null;
                switch(name)
                {
                    case "status":
                    {
                        response.setStatus(Integer.parseInt(value));
                        break;
                    }
                        
                    case "data":
                    {
                        int data = Integer.parseInt(value);
                        PrintWriter out = response.getWriter();
                        
                        int w=0;
                        while (w<data)
                        {
                            if ((data-w)>17)
                            {
                                w+=17;
                                out.print("0123456789ABCDEF\n");
                            }
                            else
                            {
                                w++;
                                out.print("\n");
                            }
                        }
                        break;
                    }

                    case "throw":
                    {
                        try
                        {
                            throw (Throwable)(Class.forName(value).newInstance());
                        }
                        catch(ServletException | IOException | Error | RuntimeException e)
                        {
                            throw e;
                        }
                        catch(Throwable e)
                        {
                            throw new ServletException(e);
                        }
                    }
                    case "flush":
                    {
                        response.flushBuffer();
                        break;
                    }
                    
                    case "read":
                    {
                        InputStream in = request.getInputStream();
                        while (in.read()>=0);
                        break;
                    }
                }
            }
        }
    }
}
