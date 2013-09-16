//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.log.Log;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;


/* ------------------------------------------------------------ */
public class Http100ContinueTest
{
    private static final int TIMEOUT = 500;
    
    private static final String CONTENT = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. "
            + "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque "
            + "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. "
            + "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam "
            + "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate "
            + "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. "
            + "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum "
            + "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa "
            + "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam "
            + "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. "
            + "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse "
            + "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";
    
    private static TestFeature _feature;

    private static Server _server;
    private static TestHandler _handler;
    private static HttpClient _client;
    private static String _requestUrl;

    @BeforeClass
    public static void init() throws Exception
    {
        File docRoot = new File("target/test-output/docroot/");
        if (!docRoot.exists())
            assertTrue(docRoot.mkdirs());
        docRoot.deleteOnExit();
    
        _server = new Server();
        Connector connector = new SelectChannelConnector();
        _server.addConnector(connector);
    
        _handler = new TestHandler();
        _server.setHandler(_handler);
    
        _server.start();
    
        _client = new HttpClient();
        _client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _client.setTimeout(TIMEOUT);
        _client.setMaxRetries(0);
        _client.start();

        _requestUrl = "http://localhost:" + connector.getLocalPort() + "/";

    }
    
    @AfterClass
    public static void destroy() throws Exception
    {
        _client.stop();
        
        _server.stop();
        _server.join();
    }
    
    @Test
    public void testSuccess() throws Exception
    {
        // Handler to send CONTINUE 100
        _feature = TestFeature.CONTINUE;
        
        ContentExchange exchange = sendExchange();

        int state = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_COMPLETED, state);
        
        int responseStatus = exchange.getResponseStatus();
        assertEquals(HttpStatus.OK_200,responseStatus);

        String content = exchange.getResponseContent();
        assertEquals(Http100ContinueTest.CONTENT,content);
    }

    @Test
    public void testMissingContinue() throws Exception
    {
        // Handler does not send CONTINUE 100
        _feature = TestFeature.NORMAL;
        
        ContentExchange exchange = sendExchange();

        int state = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_COMPLETED, state);
        
        int responseStatus = exchange.getResponseStatus();
        assertEquals(HttpStatus.OK_200,responseStatus);

        String content = exchange.getResponseContent();
        assertEquals(Http100ContinueTest.CONTENT,content);
    }

    @Test
    public void testError() throws Exception
    {
        // Handler sends NOT FOUND 404 response
        _feature = TestFeature.NOTFOUND;
        
        ContentExchange exchange = sendExchange();

        int state = exchange.waitForDone();
        assertEquals(HttpExchange.STATUS_COMPLETED, state);
        
        int responseStatus = exchange.getResponseStatus();
        assertEquals(HttpStatus.NOT_FOUND_404,responseStatus);
    }

    @Test
    public void testTimeout() throws Exception
    {
        // Handler delays response till client times out
        _feature = TestFeature.TIMEOUT;
        
        final CountDownLatch expires = new CountDownLatch(1);
        ContentExchange exchange = new ContentExchange()
        {
            @Override
            protected void onExpire()
            {
                expires.countDown();
            }
        };

        configureExchange(exchange);
        _client.send(exchange);

        assertTrue(expires.await(TIMEOUT*10,TimeUnit.MILLISECONDS));
    }

    public ContentExchange sendExchange() throws Exception
    {
        ContentExchange exchange = new ContentExchange();

        configureExchange(exchange);
        _client.send(exchange);

        return exchange;
    }
    
    public void configureExchange(ContentExchange exchange)
    {
        exchange.setURL(_requestUrl);
        exchange.setMethod(HttpMethods.GET);
        exchange.addRequestHeader("User-Agent","Jetty-Client/7.0");
        exchange.addRequestHeader("Expect","100-continue"); //server to send CONTINUE 100
    }

    private static class TestHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (baseRequest.isHandled())
                return;
            
            baseRequest.setHandled(true);

            switch (_feature)
            {
                case CONTINUE:
                    // force 100 Continue response to be sent
                    request.getInputStream();
                    // next send data

                case NORMAL:
                    response.setContentType("text/plain");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().print(CONTENT);
                    break;
                    
                case NOTFOUND:
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    break;

                case TIMEOUT:
                    try
                    {
                        Thread.sleep(TIMEOUT*4);
                    }
                    catch (InterruptedException ex)
                    {
                        Log.ignore(ex);
                    }
                    break;
            }
        }
    }
    
    enum TestFeature {
        CONTINUE,
        NORMAL,
        NOTFOUND,
        TIMEOUT
    }
}
