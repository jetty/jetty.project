// ========================================================================
// Copyright (c) Webtide LLC
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


/* ------------------------------------------------------------ */
public class Http100ContinueTest
{
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
    
    private Server _server;
    private TestHandler _handler;
    private int _port;
    
    @Before
    public void init() throws Exception
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
    
        _port = connector.getLocalPort();
    }
    
    @After
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }
    
    @Test
    public void testSelectConnector() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.start();
        try
        {
            String requestUrl = "http://localhost:" + _port + "/header";
    
            ContentExchange exchange = new ContentExchange();
            exchange.setURL(requestUrl);
            exchange.setMethod(HttpMethods.GET);
            exchange.addRequestHeader("User-Agent","Jetty-Client/7.0");
            exchange.addRequestHeader("Expect","100-continue"); //server to send CONTINUE 100
    
            httpClient.send(exchange);
    
            int state = exchange.waitForDone();
            assertEquals(HttpExchange.STATUS_COMPLETED, state);
            int responseStatus = exchange.getResponseStatus();
            assertEquals(HttpStatus.OK_200,responseStatus);
    
            String content = exchange.getResponseContent();
    
            assertEquals(Http100ContinueTest.CONTENT,content);
        }
        finally
        {
            httpClient.stop();
        }
    }

    private static class TestHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (baseRequest.isHandled())
                return;
            
            // force 100 Continue response to be sent
            request.getInputStream();
    
            response.setContentType("text/plain");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print(CONTENT);
    
            baseRequest.setHandled(true);
        }
    
    }
}
