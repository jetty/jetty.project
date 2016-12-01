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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.net.HttpCookie;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.common.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.EchoSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class RequestHeadersTest
{
    private static class EchoCreator implements WebSocketCreator
    {
        private UpgradeRequest lastRequest;
        private UpgradeResponse lastResponse;
        private EchoSocket echoSocket = new EchoSocket();

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            this.lastRequest = req;
            this.lastResponse = resp;
            return echoSocket;
        }

        public UpgradeRequest getLastRequest()
        {
            return lastRequest;
        }

        @SuppressWarnings("unused")
        public UpgradeResponse getLastResponse()
        {
            return lastResponse;
        }
    }

    public static class EchoRequestServlet extends WebSocketServlet
    {
        /**
         * 
         */
        private static final long serialVersionUID = -6575001979901924179L;
        private final WebSocketCreator creator;

        public EchoRequestServlet(WebSocketCreator creator)
        {
            this.creator = creator;
        }

        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this.creator);
        }
    }

    private static SimpleServletServer server;
    private static EchoCreator echoCreator;

    @BeforeClass
    public static void startServer() throws Exception
    {
        echoCreator = new EchoCreator();
        server = new SimpleServletServer(new EchoRequestServlet(echoCreator));
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    @Test
    public void testAccessRequestCookies() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setTimeout(1,TimeUnit.SECONDS);

        try
        {
            client.connect();
            client.addHeader("Cookie: fruit=Pear; type=Anjou\r\n");
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            UpgradeRequest req = echoCreator.getLastRequest();
            Assert.assertThat("Last Request",req,notNullValue());
            List<HttpCookie> cookies = req.getCookies();
            Assert.assertThat("Request cookies",cookies,notNullValue());
            Assert.assertThat("Request cookies.size",cookies.size(),is(2));
            for (HttpCookie cookie : cookies)
            {
                Assert.assertThat("Cookie name",cookie.getName(),anyOf(is("fruit"),is("type")));
                Assert.assertThat("Cookie value",cookie.getValue(),anyOf(is("Pear"),is("Anjou")));
            }
        }
        finally
        {
            client.close();
        }
    }
}
