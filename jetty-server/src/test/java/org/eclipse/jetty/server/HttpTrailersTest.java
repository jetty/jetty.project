//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HttpTrailersTest
{
    private Server server;
    private ServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testServletRequestTrailers() throws Exception
    {
        String trailerName = "Trailer";
        String trailerValue = "value";
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);

                // Read the content first.
                ServletInputStream input = jettyRequest.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }

                // Now the trailers can be accessed.
                HttpFields trailers = jettyRequest.getTrailers();
                Assert.assertNotNull(trailers);
                Assert.assertEquals(trailerValue, trailers.get(trailerName));
            }
        });

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setSoTimeout(5000);

            String request = "" +
                    "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "0\r\n" +
                    trailerName + ": " + trailerValue + "\r\n" +
                    "\r\n";
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client.getInputStream()));
            Assert.assertNotNull(response);
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testHugeTrailer() throws Exception
    {
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            protected void doNonErrorHandle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);

                try
                {
                    // EOF will not be reached because of the huge trailer.
                    ServletInputStream input = jettyRequest.getInputStream();
                    while (true)
                    {
                        int read = input.read();
                        if (read < 0)
                            break;
                    }
                    Assert.fail();
                }
                catch (IOException x)
                {
                    // Expected.
                }
            }
        });

        char[] huge = new char[1024 * 1024];
        Arrays.fill(huge, 'X');
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.setSoTimeout(5000);

            try
            {
                String request = "" +
                    "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "0\r\n" +
                    "Trailer: " + new String(huge) + "\r\n" +
                    "\r\n";
                OutputStream output = client.getOutputStream();
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();
                
                HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client.getInputStream()));
                Assert.assertNotNull(response);
                Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
            }
            catch(Exception e)
            {
                // May be thrown if write fails and error handling is aborted
            }
        }
    }
}
