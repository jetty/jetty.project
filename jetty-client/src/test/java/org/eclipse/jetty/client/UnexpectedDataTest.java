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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;


/**
 * Functional testing for HttpExchange.
 *
 * @author Matthew Purland
 * @author Greg Wilkins
 */
public class UnexpectedDataTest extends TestCase
{
    protected int _maxConnectionsPerAddress = 1;
    protected String _scheme = "http://";
    protected Server _server;
    protected int _port;
    protected HttpClient _httpClient;
    protected Connector _connector;
    protected AtomicInteger _count = new AtomicInteger();

    protected void setUp() throws Exception
    {
        startServer();
        _httpClient=new HttpClient();
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setMaxConnectionsPerAddress(_maxConnectionsPerAddress);
        _httpClient.start();
    }

    protected void tearDown() throws Exception
    {
        _httpClient.stop();
        Thread.sleep(500);
        stopServer();
    }

    public void testUnexpectedData() throws Exception
    {
        for (int i=0; i<4; i++)
        {
            final CountDownLatch done=new CountDownLatch(1);
            ContentExchange httpExchange=new ContentExchange()
            {
                protected void onResponseComplete() throws IOException
                {
                    super.onResponseComplete();

                    done.countDown();
                }
            };
            httpExchange.setURL(_scheme+"localhost:"+_port+"/?i="+i);
            httpExchange.setMethod(HttpMethods.GET);
            _httpClient.send(httpExchange);
            
            done.await(1,TimeUnit.SECONDS);
            
            int status = httpExchange.getStatus();
            String result=httpExchange.getResponseContent();
            assertEquals("i="+i,0,result.indexOf("<hello>"));
            assertEquals("i="+i,result.length()-10,result.indexOf("</hello>"));
            assertEquals(HttpExchange.STATUS_COMPLETED, status);
            
            Thread.sleep(5);
        }
    }

    public static void copyStream(InputStream in, OutputStream out)
    {
        try
        {
            byte[] buffer=new byte[1024];
            int len;
            while ((len=in.read(buffer))>=0)
            {
                out.write(buffer,0,len);
            }
        }
        catch (EofException e)
        {
            System.err.println(e);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    protected void newServer() throws Exception
    {
        _server=new Server();
        _server.setGracefulShutdown(500);
        _connector=new SelectChannelConnector();

        _connector.setPort(0);
        _server.setConnectors(new Connector[] { _connector });
    }

    protected void startServer() throws Exception
    {
        newServer();
        _server.setHandler(new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                int i=0;
                try
                {
                    baseRequest.setHandled(true);
                    response.setStatus(200);
                    _count.incrementAndGet();
                    
                    if (request.getMethod().equalsIgnoreCase("GET"))
                    {
                        StringBuffer buffer = new StringBuffer();
                        buffer.append("<hello>\r\n");
                        for (; i<100; i++)
                        {
                            buffer.append("  <world>"+i+"</world>\r\n");
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
                        int size=request.getContentLength();
                        ByteArrayOutputStream bout = new ByteArrayOutputStream(size>0?size:32768);
                        IO.copy(request.getInputStream(),bout);
                        response.getOutputStream().write(bout.toByteArray());
                    }
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                    throw e;
                }
                catch(Throwable e)
                {
                    e.printStackTrace();
                    throw new ServletException(e);
                }
                finally
                {
                    // System.err.println("HANDLED "+i);
                }
            }
        });
        _server.start();
        _port=_connector.getLocalPort();
    }

    private void stopServer() throws Exception
    {
        _server.stop();
    }

}
