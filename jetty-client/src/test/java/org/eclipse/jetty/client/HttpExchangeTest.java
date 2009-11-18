// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

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
import org.eclipse.jetty.client.security.ProxyAuthorization;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

/**
 * Functional testing for HttpExchange.
 */
public class HttpExchangeTest extends TestCase
{
    private boolean _stress=Boolean.getBoolean("STRESS");
    protected int _maxConnectionsPerAddress = 2;
    protected String _scheme = "http://";
    protected Server _server;
    protected int _port;
    protected HttpClient _httpClient;
    protected Connector _connector;
    protected AtomicInteger _count = new AtomicInteger();

    @Override
    protected void setUp() throws Exception
    {
        startServer();
        _httpClient=new HttpClient();
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setMaxConnectionsPerAddress(_maxConnectionsPerAddress);
        _httpClient.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        _httpClient.stop();
        Thread.sleep(500);
        stopServer();
    }

    public void testResetNewExchange() throws Exception
    {
        HttpExchange exchange = new HttpExchange();
        exchange.reset();
    }

    public void testPerf() throws Exception
    {
        if (_stress)
        {
            sender(1,false);
            sender(1,true);
            sender(100,false);
            sender(100,true);
            sender(10000,false);
            sender(10000,true);
        }
        else
        {
            sender(1,false);
            sender(1,true);
            sender(10,false);
            sender(10,true);
            sender(20,false);
            sender(20,true);
        }
    }

    /**
     * Test sending data through the exchange.
     *
     * @throws IOException
     */
    public void sender(final int nb,final boolean close) throws Exception
    {
        _count.set(0);
        final CountDownLatch complete=new CountDownLatch(nb);
        final CountDownLatch latch=new CountDownLatch(nb);
        HttpExchange[] httpExchange = new HttpExchange[nb];
        long start=System.currentTimeMillis();
        for (int i=0; i<nb; i++)
        {
            final int n=i;

            httpExchange[n]=new HttpExchange()
            {
                String result="pending";
                int len=0;
                @Override
                protected void onRequestCommitted()
                {
                    result="committed";
                    // System.err.println(n+" Request committed: "+close);
                }

                @Override
                protected void onRequestComplete() throws IOException
                {
                    result="sent";
                }

                @Override
                protected void onResponseStatus(Buffer version, int status, Buffer reason)
                {
                    result="status";
                    // System.err.println(n+" Response Status: " + version+" "+status+" "+reason);
                }

                @Override
                protected void onResponseHeader(Buffer name, Buffer value)
                {
                    // System.err.println(n+" Response header: " + name + " = " + value);
                }

                @Override
                protected void onResponseHeaderComplete() throws IOException
                {
                    result="content";
                    super.onResponseHeaderComplete();
                }

                @Override
                protected void onResponseContent(Buffer content)
                {
                    len+=content.length();
                    // System.err.println(n+" Response content:" + content.length());
                }

                @Override
                protected void onResponseComplete()
                {
                    result="complete";
                    // System.err.println(n+" Response completed "+len);
                    if (len==2009)
                        latch.countDown();
                    else
                        System.err.println(n+" ONLY "+len);
                    complete.countDown();
                }

                @Override
                protected void onConnectionFailed(Throwable ex)
                {
                    complete.countDown();
                    result="failed";
                    System.err.println(n+" FAILED "+ex);
                    super.onConnectionFailed(ex);
                }

                @Override
                protected void onException(Throwable ex)
                {
                    complete.countDown();
                    result="excepted";
                    System.err.println(n+" EXCEPTED "+ex);
                    super.onException(ex);
                }

                @Override
                protected void onExpire()
                {
                    complete.countDown();
                    result="expired";
                    System.err.println(n+" EXPIRED "+len);
                    super.onExpire();
                }

                @Override
                public String toString()
                {
                    return n+" "+result+" "+len;
                }
            };

            httpExchange[n].setURL(_scheme+"localhost:"+_port+"/"+n);
            httpExchange[n].addRequestHeader("arbitrary","value");
            if (close)
                httpExchange[n].setRequestHeader("Connection","close");

            _httpClient.send(httpExchange[n]);
        }

        assertTrue(complete.await(45,TimeUnit.SECONDS));

        long elapsed=System.currentTimeMillis()-start;
        // make windows-friendly ... System.currentTimeMillis() on windows is dope!
        if(elapsed>0)
            System.err.println(nb+"/"+_count+" c="+close+" rate="+(nb*1000/elapsed));

        assertEquals("nb="+nb+" close="+close,0,latch.getCount());
    }

    public void testPostWithContentExchange() throws Exception
    {
        for (int i=0;i<200;i++)
        {
            ContentExchange httpExchange=new ContentExchange();
            //httpExchange.setURL(_scheme+"localhost:"+_port+"/");
            httpExchange.setURL(_scheme+"localhost:"+_port);
            httpExchange.setMethod(HttpMethods.POST);
            httpExchange.setRequestContent(new ByteArrayBuffer("<hello />"));
            _httpClient.send(httpExchange);
            int status = httpExchange.waitForDone();
            //httpExchange.waitForStatus(HttpExchange.STATUS_COMPLETED);
            String result=httpExchange.getResponseContent();
            assertEquals(HttpExchange.STATUS_COMPLETED, status);
            assertEquals("i="+i,"<hello />",result);
        }
    }

    public void testGetWithContentExchange() throws Exception
    {
        for (int i=0;i<200;i++)
        {
            ContentExchange httpExchange=new ContentExchange();
            httpExchange.setURL(_scheme+"localhost:"+_port+"/?i="+i);
            httpExchange.setMethod(HttpMethods.GET);
            _httpClient.send(httpExchange);
            int status = httpExchange.waitForDone();
            //httpExchange.waitForStatus(HttpExchange.STATUS_COMPLETED);
            String result=httpExchange.getResponseContent();
            assertEquals("i="+i,0,result.indexOf("<hello>"));
            assertEquals("i="+i,result.length()-10,result.indexOf("</hello>"));
            assertEquals(HttpExchange.STATUS_COMPLETED, status);
            Thread.sleep(5);
        }
    }

    public void testSun() throws Exception
    {
            ContentExchange httpExchange=new ContentExchange();
            httpExchange.setURL(_scheme+"www.sun.com/");
            httpExchange.setMethod(HttpMethods.GET);
            _httpClient.send(httpExchange);
            int status = httpExchange.waitForDone();
            String result=httpExchange.getResponseContent();
            assertTrue(result.indexOf("<title>Sun Microsystems</title>")>0);
            assertEquals(HttpExchange.STATUS_COMPLETED, status);
            assertEquals(HttpStatus.OK_200,httpExchange.getResponseStatus());

    }

    public void testProxy() throws Exception
    {
        if (_scheme.equals("https://"))
            return;
        try
        {
            _httpClient.setProxy(new Address("127.0.0.1",_port));
            _httpClient.setProxyAuthentication(new ProxyAuthorization("user","password"));

            ContentExchange httpExchange=new ContentExchange();
            httpExchange.setAddress(new Address("jetty.eclipse.org",8080));
            httpExchange.setMethod(HttpMethods.GET);
            httpExchange.setURI("/jetty-6");
            _httpClient.send(httpExchange);
            int status = httpExchange.waitForDone();
            //httpExchange.waitForStatus(HttpExchange.STATUS_COMPLETED);
            String result=httpExchange.getResponseContent();
            result=result.trim();
            assertEquals(HttpExchange.STATUS_COMPLETED, status);
            assertTrue(result.startsWith("Proxy request: http://jetty.eclipse.org:8080/jetty-6"));
            assertTrue(result.endsWith("Basic dXNlcjpwYXNzd29yZA=="));
        }
        finally
        {
            _httpClient.setProxy(null);
        }
    }


    public void testReserveConnections () throws Exception
    {
       final HttpDestination destination = _httpClient.getDestination (new Address("localhost", _port), _scheme.equalsIgnoreCase("https://"));
       final org.eclipse.jetty.client.HttpConnection[] connections = new org.eclipse.jetty.client.HttpConnection[_maxConnectionsPerAddress];
       for (int i=0; i < _maxConnectionsPerAddress; i++)
       {
           connections[i] = destination.reserveConnection(200);
           assertNotNull(connections[i]);
           HttpExchange ex = new ContentExchange();
           ex.setURL(_scheme+"localhost:"+_port+"/?i="+i);
           ex.setMethod(HttpMethods.GET);
           connections[i].send(ex);
       }

       //try to get a connection, and only wait 500ms, as we have
       //already reserved the max, should return null
       org.eclipse.jetty.client.HttpConnection c = destination.reserveConnection(500);
       assertNull(c);

       //unreserve first connection
       destination.returnConnection(connections[0], false);

       //reserving one should now work
       c = destination.reserveConnection(500);
       assertNotNull(c);


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
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                int i=0;
                try
                {
                    baseRequest.setHandled(true);
                    response.setStatus(200);
                    _count.incrementAndGet();

                    if (request.getServerName().equals("jetty.eclipse.org"))
                    {
                        // System.err.println("HANDLING Proxy");
                        response.getOutputStream().println("Proxy request: "+request.getRequestURL());
                        response.getOutputStream().println(request.getHeader(HttpHeaders.PROXY_AUTHORIZATION));
                    }
                    else if (request.getMethod().equalsIgnoreCase("GET"))
                    {
                        // System.err.println("HANDLING Hello "+request.getRequestURI());
                        response.getOutputStream().println("<hello>");
                        for (; i<100; i++)
                        {
                            response.getOutputStream().println("  <world>"+i+"</world");
                            if (i%20==0)
                                response.getOutputStream().flush();
                        }
                        response.getOutputStream().println("</hello>");
                    }
                    else
                    {
                        // System.err.println("HANDLING "+request.getMethod());
                        copyStream(request.getInputStream(),response.getOutputStream());
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
