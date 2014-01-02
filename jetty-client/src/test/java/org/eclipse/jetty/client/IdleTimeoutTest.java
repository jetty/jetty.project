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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * IdleTimeoutTest
 *
 * Warning - this is a slow test. Uncomment the ignore to run it.
 *
 */
public class IdleTimeoutTest
{
    public int _repetitions = 30;
    
    @Ignore
    //@Test
    public void testIdleTimeoutOnBlockingConnector() throws Exception
    {
        final HttpClient client = new HttpClient();
        client.setMaxConnectionsPerAddress(4);
        client.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        client.setTimeout(TimeUnit.SECONDS.toMillis(86400)); // very long timeout on data
        client.setIdleTimeout(500); // very short idle timeout
        client.start();

        final CountDownLatch counter = new CountDownLatch(_repetitions);
        
        Thread runner = new Thread()
        {
            public void run()
            {
                try
                {
                    for (int i=0; i<_repetitions; i++) 
                    {
                        ContentExchange exchange = new ContentExchange();
                        exchange.setURL("http://www.google.com/?i="+i);
                        client.send(exchange);
                        exchange.waitForDone();
                        counter.countDown();
                        System.err.println(counter.getCount());
                        Thread.sleep(1000); //wait long enough for idle timeout to expire   
                    }
                }
                catch (Exception e)
                {
                    Assert.fail(e.getMessage());
                }
            }
        };
       
        runner.start();
        if (!counter.await(80, TimeUnit.SECONDS))
            Assert.fail("Test did not complete in time");
        
    }

    @Test
    public void testConnectionsAreReleasedWhenExpired() throws Exception
    {
        // we need a server that times out and a client with shorter timeout settings, so we need to create new ones
        Server server = new Server();
        Connector connector = new SelectChannelConnector();
        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (request.getParameter("timeout") != null)
                {
                    try
                    {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                }
                baseRequest.setHandled(true);
                response.getWriter().write("Hello world");
            }
        });
        server.start();

        HttpClient httpClient = new HttpClient();
        httpClient.setMaxConnectionsPerAddress(1);
        httpClient.setConnectTimeout(200);
        httpClient.setTimeout(200);
        httpClient.setIdleTimeout(200);
        httpClient.start();

        String uriString =  "http://localhost:" + connector.getLocalPort() + "/";

        HttpExchange httpExchange = new HttpExchange();
        httpExchange.setURI(URI.create(uriString).resolve("?timeout=true"));
        httpExchange.setMethod(HttpMethods.GET);
        httpClient.send(httpExchange);
        int status = httpExchange.waitForDone();
        assertThat("First request expired", status, is(8));

        httpExchange = new HttpExchange();
        httpExchange.setURI(URI.create(uriString));
        httpExchange.setMethod(HttpMethods.GET);
        httpClient.send(httpExchange);
        status = httpExchange.waitForDone();
        assertThat("Second request was successful as timeout is not set", status, is(7));
    }
}
