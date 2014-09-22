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

package org.eclipse.jetty.websocket.jsr356.server;

import static org.hamcrest.Matchers.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.HttpResponse;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ConfiguratorTest
{
    private static final Logger LOG = Log.getLogger(ConfiguratorTest.class);

    public static class EmptyConfigurator extends ServerEndpointConfig.Configurator
    {
    }

    @ServerEndpoint(value = "/empty", configurator = EmptyConfigurator.class)
    public static class EmptySocket
    {
        @OnMessage
        public String echo(String message)
        {
            return message;
        }
    }

    public static class NoExtensionsConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested)
        {
            return Collections.emptyList();
        }
    }

    @ServerEndpoint(value = "/no-extensions", configurator = NoExtensionsConfigurator.class)
    public static class NoExtensionsSocket
    {
        @OnMessage
        public String echo(String message)
        {
            return message;
        }
    }

    public static class CaptureHeadersConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            super.modifyHandshake(sec,request,response);
            sec.getUserProperties().put("request-headers",request.getHeaders());
        }
    }

    @ServerEndpoint(value = "/capture-request-headers", configurator = CaptureHeadersConfigurator.class)
    public static class CaptureHeadersSocket
    {
        @OnMessage
        public String getHeaders(Session session, String headerKey)
        {
            StringBuilder response = new StringBuilder();

            response.append("Request Header [").append(headerKey).append("]: ");
            @SuppressWarnings("unchecked")
            Map<String, List<String>> headers = (Map<String, List<String>>)session.getUserProperties().get("request-headers");
            if (headers == null)
            {
                response.append("<no headers found in session.getUserProperties()>");
            }
            else
            {
                List<String> values = headers.get(headerKey);
                if (values == null)
                {
                    response.append("<header not found>");
                }
                else
                {
                    response.append(QuoteUtil.join(values,","));
                }
            }

            return response.toString();
        }
    }

    public static class ProtocolsConfigurator extends ServerEndpointConfig.Configurator
    {
        public static AtomicReference<String> seenProtocols = new AtomicReference<>();
        
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            super.modifyHandshake(sec,request,response);
        }
        
        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested)
        {
            String seen = QuoteUtil.join(requested,",");
            seenProtocols.compareAndSet(null,seen);
            return super.getNegotiatedSubprotocol(supported,requested);
        }
    }

    @ServerEndpoint(value = "/protocols", configurator = ProtocolsConfigurator.class)
    public static class ProtocolsSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            StringBuilder response = new StringBuilder();
            response.append("Requested Protocols: [").append(ProtocolsConfigurator.seenProtocols.get()).append("]");
            return response.toString();
        }
    }
    
    public static class UniqueUserPropsConfigurator extends ServerEndpointConfig.Configurator
    {
        private AtomicInteger upgradeCount = new AtomicInteger(0);
        
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            int upgradeNum = upgradeCount.addAndGet(1);
            LOG.debug("Upgrade Num: {}", upgradeNum);
            sec.getUserProperties().put("upgradeNum",Integer.toString(upgradeNum));
            switch(upgradeNum) {
                case 1: sec.getUserProperties().put("apple", "fruit from tree"); break;
                case 2: sec.getUserProperties().put("blueberry", "fruit from bush"); break;
                case 3: sec.getUserProperties().put("strawberry", "fruit from annual"); break;
                default: sec.getUserProperties().put("fruit"+upgradeNum, "placeholder"); break;
            }
            
            super.modifyHandshake(sec,request,response);
        }
    }
    
    @ServerEndpoint(value = "/unique-user-props", configurator = UniqueUserPropsConfigurator.class)
    public static class UniqueUserPropsSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            String value = (String)session.getUserProperties().get(msg);
            StringBuilder response = new StringBuilder();
            response.append("Requested User Property: [").append(msg).append("] = ");
            if (value == null)
            {
                response.append("<null>");
            }
            else
            {
                response.append('"').append(value).append('"');
            }
            return response.toString();
        }
    }
    
    public static class AddrConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            InetSocketAddress local = (InetSocketAddress)sec.getUserProperties().get(JsrCreator.PROP_LOCAL_ADDRESS);
            InetSocketAddress remote = (InetSocketAddress)sec.getUserProperties().get(JsrCreator.PROP_REMOTE_ADDRESS);
            
            sec.getUserProperties().put("found.local", local);
            sec.getUserProperties().put("found.remote", remote);
            
            super.modifyHandshake(sec,request,response);
        }
    }
    
    @ServerEndpoint(value = "/addr", configurator = AddrConfigurator.class)
    public static class AddressSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            StringBuilder response = new StringBuilder();
            appendPropValue(session,response,"javax.websocket.endpoint.localAddress");
            appendPropValue(session,response,"javax.websocket.endpoint.remoteAddress");
            appendPropValue(session,response,"found.local");
            appendPropValue(session,response,"found.remote");
            return response.toString();
        }

        private void appendPropValue(Session session, StringBuilder response, String key)
        {
            InetSocketAddress value = (InetSocketAddress)session.getUserProperties().get(key);

            response.append("[").append(key).append("] = ");
            response.append(toSafeAddr(value));
            response.append(System.lineSeparator());
        }
    }
    
    private static Server server;
    private static URI baseServerUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        container.addEndpoint(CaptureHeadersSocket.class);
        container.addEndpoint(EmptySocket.class);
        container.addEndpoint(NoExtensionsSocket.class);
        container.addEndpoint(ProtocolsSocket.class);
        container.addEndpoint(UniqueUserPropsSocket.class);
        container.addEndpoint(AddressSocket.class);

        server.start();
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        baseServerUri = new URI(String.format("ws://%s:%d/",host,port));
        if (LOG.isDebugEnabled())
            LOG.debug("Server started on {}",baseServerUri);
    }

    public static String toSafeAddr(InetSocketAddress addr)
    {
        if (addr == null)
        {
            return "<null>";
        }
        return String.format("%s:%d",addr.getAddress().getHostAddress(),addr.getPort());
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testEmptyConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/empty");

        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.addExtensions("identity");
            client.connect();
            client.sendStandardRequest();
            HttpResponse response = client.readResponseHeader();
            Assert.assertThat("response.extensions",response.getExtensionsHeader(),is("identity"));
        }
    }

    @Test
    public void testNoExtensionsConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/no-extensions");

        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.addExtensions("identity");
            client.connect();
            client.sendStandardRequest();
            HttpResponse response = client.readResponseHeader();
            Assert.assertThat("response.extensions",response.getExtensionsHeader(),nullValue());
        }
    }

    @Test
    public void testCaptureRequestHeadersConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/capture-request-headers");

        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.addHeader("X-Dummy: Bogus\r\n");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("X-Dummy"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Request Header [X-Dummy]: \"Bogus\""));
        }
    }
    
    @Test
    public void testUniqueUserPropsConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/unique-user-props");

        // First request
        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("apple"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested User Property: [apple] = \"fruit from tree\""));
        }
        
        // Second request
        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("apple"));
            client.write(new TextFrame().setPayload("blueberry"));
            EventQueue<WebSocketFrame> frames = client.readFrames(2,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            // should have no value
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested User Property: [apple] = <null>"));
            
            frame = frames.poll();
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested User Property: [blueberry] = \"fruit from bush\""));
        }
    }
    
    @Test
    public void testUserPropsAddress() throws Exception
    {
        URI uri = baseServerUri.resolve("/addr");

        // First request
        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();
            
            InetSocketAddress expectedLocal = client.getLocalSocketAddress();
            InetSocketAddress expectedRemote = client.getRemoteSocketAddress();

            client.write(new TextFrame().setPayload("addr"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            
            StringWriter expected = new StringWriter();
            PrintWriter out = new PrintWriter(expected);
            // local <-> remote are opposite on server (duh)
            out.printf("[javax.websocket.endpoint.localAddress] = %s%n", toSafeAddr(expectedRemote));
            out.printf("[javax.websocket.endpoint.remoteAddress] = %s%n", toSafeAddr(expectedLocal));
            out.printf("[found.local] = %s%n",toSafeAddr(expectedRemote));
            out.printf("[found.remote] = %s%n",toSafeAddr(expectedLocal));
            
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is(expected.toString()));
        }
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, as seen in RFC-6455, 1 protocol
     */
    @Test
    public void testProtocol_Single() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.addHeader("Sec-WebSocket-Protocol: echo\r\n");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("getProtocols"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\"]"));
        }
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, as seen in RFC-6455, 3 protocols
     */
    @Test
    public void testProtocol_Triple() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.addHeader("Sec-WebSocket-Protocol: echo, chat, status\r\n");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("getProtocols"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        }
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, using all lowercase header
     */
    @Test
    public void testProtocol_LowercaseHeader() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.addHeader("sec-websocket-protocol: echo, chat, status\r\n");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("getProtocols"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        }
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, using non-spec case header
     */
    @Test
    public void testProtocol_AltHeaderCase() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        try (BlockheadClient client = new BlockheadClient(uri))
        {
            client.addHeader("Sec-Websocket-Protocol: echo, chat, status\r\n");
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(new TextFrame().setPayload("getProtocols"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);
            WebSocketFrame frame = frames.poll();
            Assert.assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        }
    }
}
