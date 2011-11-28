// ========================================================================
// Copyright 2006-2007 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.eclipse.jetty.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Functional testing for HttpExchange.
 *
 * @author Matthew Purland
 * @author Greg Wilkins
 */
public class UnexpectedDataTest
{
    private Server _server;
    private int _port;
    private HttpClient _httpClient;
    private Connector _connector;
    private AtomicInteger _count = new AtomicInteger();

    @Before
    public void setUp() throws Exception
    {
        startServer();
        _httpClient = new HttpClient();
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setMaxConnectionsPerAddress(1);
        _httpClient.start();
    }

    @After
    public void tearDown() throws Exception
    {
        _httpClient.stop();
        Thread.sleep(500);
        stopServer();
    }

    @Test
    public void testUnexpectedData() throws Exception
    {
        for (int i = 0; i < 4; ++i)
        {
            final CountDownLatch done = new CountDownLatch(1);
            ContentExchange httpExchange = new ContentExchange()
            {
                protected void onResponseComplete() throws IOException
                {
                    super.onResponseComplete();
                    done.countDown();
                }
            };
            httpExchange.setURL("http://localhost:" + _port + "/?i=" + i);
            httpExchange.setMethod(HttpMethods.GET);
            _httpClient.send(httpExchange);

            Assert.assertTrue(done.await(1000, TimeUnit.SECONDS));

            int status = httpExchange.getStatus();
            String result = httpExchange.getResponseContent();
            Assert.assertEquals("i=" + i, 0, result.indexOf("<hello>"));
            Assert.assertEquals("i=" + i, result.length() - 10, result.indexOf("</hello>"));
            Assert.assertEquals(HttpExchange.STATUS_COMPLETED, status);

            // Give the client the time to read -1 from server before issuing the next request
            // There is currently no simple way to be notified of connection closed.
            Thread.sleep(500);
        }
    }

    protected void newServer() throws Exception
    {
        _server = new Server();
        _server.setGracefulShutdown(500);
        _connector = new SelectChannelConnector();
        _connector.setPort(0);
        _server.setConnectors(new Connector[]{_connector});
    }

    protected void startServer() throws Exception
    {
        newServer();
        _server.setHandler(new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException
            {
                int i = 0;
                try
                {
                    baseRequest.setHandled(true);
                    response.setStatus(200);
                    _count.incrementAndGet();

                    if (request.getMethod().equalsIgnoreCase("GET"))
                    {
                        StringBuilder buffer = new StringBuilder();
                        buffer.append("<hello>\r\n");
                        for (; i < 100; i++)
                        {
                            buffer.append("  <world>").append(i).append("</world>\r\n");
                        }
                        buffer.append("</hello>\r\n");

                        byte[] buff = buffer.toString().getBytes();
                        response.setContentLength(buff.length);

                        buffer.append("extra data");
                        buff = buffer.toString().getBytes();

                        OutputStream out = response.getOutputStream();
                        out.write(buff, 0, buff.length);
                        out.flush();
                    }
                    else
                    {
                        response.setContentType(request.getContentType());
                        int size = request.getContentLength();
                        ByteArrayOutputStream bout = new ByteArrayOutputStream(size > 0 ? size : 32768);
                        IO.copy(request.getInputStream(), bout);
                        response.getOutputStream().write(bout.toByteArray());
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    throw e;
                }
                catch (Throwable e)
                {
                    e.printStackTrace();
                    throw new ServletException(e);
                }
            }
        });
        _server.start();
        _port = _connector.getLocalPort();
    }

    private void stopServer() throws Exception
    {
        _server.stop();
    }
}
