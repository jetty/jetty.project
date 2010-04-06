// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;

public class HttpHeadersTest extends TestCase
{
    private static String _content = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. "
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

    private File _docRoot;
    private Server _server;
    private Connector _connector;
    private TestHeaderHandler _handler;
    private int _port;

    public void setUp() throws Exception
    {
        _docRoot = new File("target/test-output/docroot/");
        _docRoot.mkdirs();
        _docRoot.deleteOnExit();

        startServer();
    }

    public void tearDown() throws Exception
    {
        stopServer();
    }

    public void testHttpHeaders() throws Exception
    {
        HttpClient client = new HttpClient();
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        client.start();

        String requestUrl = "http://localhost:" + _port + "/header";

        ContentExchange exchange = new ContentExchange();
        exchange.setURL(requestUrl);
        exchange.setMethod(HttpMethods.GET);
        exchange.addRequestHeader("User-Agent","Jetty-Client/7.0");

        client.send(exchange);
        int state = exchange.waitForDone();

        String content = "";
        int responseStatus = exchange.getResponseStatus();
        if (responseStatus == HttpStatus.OK_200)
        {
            content = exchange.getResponseContent();
        }

        assertEquals(HttpStatus.OK_200,responseStatus);
        assertEquals(_content,content);
        assertEquals("Jetty-Client/7.0",_handler.headers.get("User-Agent"));
    }

    protected void startServer() throws Exception
    {
        _server = new Server(0);

        _connector = new SelectChannelConnector();
        _server.addConnector(_connector);

        _handler = new TestHeaderHandler();
        _server.setHandler(_handler);

        _server.start();

        _port = _connector.getLocalPort();
    }

    protected void stopServer() throws Exception
    {
        if (_server != null)
        {
            _server.stop();
            _server = null;
        }
    }

    private static class TestHeaderHandler extends AbstractHandler
    {
        protected Map<String, String> headers;

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (baseRequest.isHandled())
                return;

            headers = new HashMap<String, String>();
            for (Enumeration e = request.getHeaderNames(); e.hasMoreElements();)
            {
                String name = (String)e.nextElement();
                headers.put(name,request.getHeader(name));
            }

            response.setContentType("text/plain");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print(_content);

            baseRequest.setHandled(true);
        }

    }
}
