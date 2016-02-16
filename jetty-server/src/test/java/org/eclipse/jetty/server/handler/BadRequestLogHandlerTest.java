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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Testing oddball request scenarios (like error 400) where the error should
 * be logged 
 */
@RunWith(Parameterized.class)
@Ignore
public class BadRequestLogHandlerTest
{
    private static final Logger LOG = Log.getLogger(BadRequestLogHandlerTest.class);
    
    public static class CaptureLog extends AbstractLifeCycle implements RequestLog
    {
        public List<String> captured = new ArrayList<>();

        @Override
        public void log(Request request, Response response)
        {
            int status = response.getCommittedMetaData().getStatus();
            captured.add(String.format("%s %s %s %03d",request.getMethod(),request.getHttpURI(),request.getProtocol(),status));
        }
    }
    
    private static class HelloHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");
            response.getWriter().print("Hello World");
            baseRequest.setHandled(true);
        }
    }
    
    @Parameters
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        
        data.add(new String[]{ "GET /bad VER\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n\r\n" , 
                "GET <invalidrequest> HTTP/1.1 400" });
        data.add(new String[]{ "GET /%%adsasd HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n\r\n" , 
                "GET <invalidrequest> HTTP/1.1 400" });
        
        return data;
    }
    
    @Parameter(0)
    public String requestHeader;
    
    @Parameter(1)
    public String expectedLog;
    
    @Test(timeout=4000)
    public void testLogHandler() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });

        CaptureLog captureLog = new CaptureLog();

        RequestLogHandler requestLog = new RequestLogHandler();
        requestLog.setRequestLog(captureLog);
        
        requestLog.setHandler(new HelloHandler());

        server.setHandler(requestLog);

        try
        {
            server.start();
            
            String host = connector.getHost();
            if (host == null)
            {
                host = "localhost";
            }

            InetAddress destAddr = InetAddress.getByName(host);
            int port = connector.getLocalPort();
            SocketAddress endpoint = new InetSocketAddress(destAddr,port);

            Socket socket = new Socket();
            socket.setSoTimeout(1000);
            socket.connect(endpoint);

            try(OutputStream out = socket.getOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(out,StandardCharsets.UTF_8);
                InputStream in = socket.getInputStream();
                InputStreamReader reader = new InputStreamReader(in,StandardCharsets.UTF_8))
            {
                StringReader request = new StringReader(requestHeader);
                IO.copy(request,writer);
                writer.flush();
                StringWriter response = new StringWriter();
                IO.copy(reader,response);
                LOG.info("Response: {}",response);
            } finally {
                socket.close();
            }

            assertRequestLog(captureLog);
        }
        finally
        {
            server.stop();
        }
    }
    
    private void assertRequestLog(CaptureLog captureLog)
    {
        int captureCount = captureLog.captured.size();

        if (captureCount != 1)
        {
            LOG.warn("Capture Log size is {}, expected to be 1",captureCount);
            if (captureCount > 1)
            {
                for (int i = 0; i < captureCount; i++)
                {
                    LOG.warn("[{}] {}",i,captureLog.captured.get(i));
                }
            }
            assertThat("Capture Log Entry Count",captureLog.captured.size(),is(1));
        }

        String actual = captureLog.captured.get(0);
        assertThat("Capture Log",actual,is(expectedLog));
    }
}
