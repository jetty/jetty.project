//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import static org.hamcrest.Matchers.not;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DefaultHandlerTest
{
    private Server server;
    private ServerConnector connector;
    private DefaultHandler handler;

    @Before
    public void before() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        handler = new DefaultHandler();
        HandlerCollection handlers = new HandlerCollection();
        handlers.setHandlers(new Handler[] { contexts, handler });
        server.setHandler(handlers);

        handler.setServeIcon(true);
        handler.setShowContexts(true);
        
        contexts.addHandler(new ContextHandler("/foo"));
        contexts.addHandler(new ContextHandler("/bar"));
        
        server.start();
    }
    
    @After
    public void after() throws Exception
    {
        server.stop();
    }

    @Test
    public void testRoot() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            Assert.assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            Assert.assertEquals("text/html;charset=ISO-8859-1", response.get(HttpHeader.CONTENT_TYPE));

            String content = new String(response.getContentBytes(),StandardCharsets.ISO_8859_1);
            Assert.assertThat(content,containsString("Contexts known to this server are:"));
            Assert.assertThat(content,containsString("/foo"));
            Assert.assertThat(content,containsString("/bar"));
        }
    }
    
    @Test
    public void testSomePath() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request = "" +
                "GET /some/path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            Assert.assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            Assert.assertEquals("text/html;charset=ISO-8859-1", response.get(HttpHeader.CONTENT_TYPE));

            String content = new String(response.getContentBytes(),StandardCharsets.ISO_8859_1);
            Assert.assertThat(content,not(containsString("Contexts known to this server are:")));
            Assert.assertThat(content,not(containsString("/foo")));
            Assert.assertThat(content,not(containsString("/bar")));
        }
    }
    
    @Test
    public void testFavIcon() throws Exception
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request = "" +
                "GET /favicon.ico HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);

            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
            Assert.assertEquals("image/x-icon", response.get(HttpHeader.CONTENT_TYPE));
        }
    }

}
