//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

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
        public String echo(Session session, String message)
        {
            List<Extension> negotiatedExtensions = session.getNegotiatedExtensions();
            if (negotiatedExtensions == null)
            {
                return "negotiatedExtensions=null";
            }
            else
            {
                return "negotiatedExtensions=" + negotiatedExtensions.stream()
                    .map((ext) -> ext.getName())
                    .collect(Collectors.joining(",", "[", "]"));
            }
        }
    }

    public static class CaptureHeadersConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            super.modifyHandshake(sec, request, response);
            sec.getUserProperties().put("request-headers", request.getHeaders());
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
                    response.append(QuoteUtil.join(values, ","));
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
            super.modifyHandshake(sec, request, response);
        }

        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested)
        {
            String seen = QuoteUtil.join(requested, ",");
            seenProtocols.compareAndSet(null, seen);
            return super.getNegotiatedSubprotocol(supported, requested);
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
            sec.getUserProperties().put("upgradeNum", Integer.toString(upgradeNum));
            switch (upgradeNum)
            {
                case 1:
                    sec.getUserProperties().put("apple", "fruit from tree");
                    break;
                case 2:
                    sec.getUserProperties().put("blueberry", "fruit from bush");
                    break;
                case 3:
                    sec.getUserProperties().put("strawberry", "fruit from annual");
                    break;
                default:
                    sec.getUserProperties().put("fruit" + upgradeNum, "placeholder");
                    break;
            }

            super.modifyHandshake(sec, request, response);
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

            super.modifyHandshake(sec, request, response);
        }
    }

    @ServerEndpoint(value = "/addr", configurator = AddrConfigurator.class)
    public static class AddressSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            StringBuilder response = new StringBuilder();
            appendPropValue(session, response, "javax.websocket.endpoint.localAddress");
            appendPropValue(session, response, "javax.websocket.endpoint.remoteAddress");
            appendPropValue(session, response, "found.local");
            appendPropValue(session, response, "found.remote");
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

    public static class SelectedProtocolConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response)
        {
            List<String> selectedProtocol = response.getHeaders().get("Sec-WebSocket-Protocol");
            String protocol = "<>";
            if (selectedProtocol != null && !selectedProtocol.isEmpty())
                protocol = selectedProtocol.get(0);
            config.getUserProperties().put("selected-subprotocol", protocol);
        }
    }

    public static class GmtTimeDecoder implements Decoder.Text<Calendar>
    {
        private TimeZone timeZone;

        @Override
        public Calendar decode(String s) throws DecodeException
        {
            if (timeZone == null)
                throw new DecodeException(s, ".init() not called");
            try
            {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                dateFormat.setTimeZone(timeZone);
                Date time = dateFormat.parse(s);
                Calendar cal = Calendar.getInstance();
                cal.setTimeZone(timeZone);
                cal.setTime(time);
                return cal;
            }
            catch (ParseException e)
            {
                throw new DecodeException(s, "Unable to decode Time", e);
            }
        }

        @Override
        public void init(EndpointConfig config)
        {
            timeZone = TimeZone.getTimeZone("GMT+0");
        }

        @Override
        public void destroy()
        {
        }

        @Override
        public boolean willDecode(String s)
        {
            return true;
        }
    }

    @ServerEndpoint(value = "/timedecoder",
        subprotocols = {"time", "gmt"},
        configurator = SelectedProtocolConfigurator.class,
        decoders = {GmtTimeDecoder.class})
    public static class TimeDecoderSocket
    {
        private TimeZone timeZone = TimeZone.getTimeZone("GMT+0");

        @OnMessage
        public String onMessage(Calendar cal)
        {
            return String.format("cal=%s", newDateFormat().format(cal.getTime()));
        }

        private SimpleDateFormat newDateFormat()
        {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd G 'at' HH:mm:ss Z", Locale.ENGLISH);
            dateFormat.setTimeZone(timeZone);
            return dateFormat;
        }
    }

    public static class ConfigNormalConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            sec.getUserProperties().put("self.configurator", this.getClass().getName());
        }
    }

    public static class ConfigOverrideConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            sec.getUserProperties().put("self.configurator", this.getClass().getName());
        }
    }

    @ServerEndpoint(value = "/config-normal",
        configurator = ConfigNormalConfigurator.class)
    public static class ConfigNormalSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            return String.format("UserProperties[self.configurator] = %s", session.getUserProperties().get("self.configurator"));
        }
    }

    private static BlockheadClient client;
    private static Server server;
    private static URI baseServerUri;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        HandlerList handlers = new HandlerList();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        handlers.addHandler(context);

        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        container.addEndpoint(CaptureHeadersSocket.class);
        container.addEndpoint(EmptySocket.class);
        container.addEndpoint(NoExtensionsSocket.class);
        container.addEndpoint(ProtocolsSocket.class);
        container.addEndpoint(UniqueUserPropsSocket.class);
        container.addEndpoint(AddressSocket.class);
        container.addEndpoint(TimeDecoderSocket.class);

        container.addEndpoint(ConfigNormalSocket.class);
        ServerEndpointConfig overrideEndpointConfig = ServerEndpointConfig.Builder
            .create(ConfigNormalSocket.class, "/config-override")
            .configurator(new ConfigOverrideConfigurator())
            .build();
        container.addEndpoint(overrideEndpointConfig);

        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);
        server.start();
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        baseServerUri = new URI(String.format("ws://%s:%d/", host, port));
        if (LOG.isDebugEnabled())
            LOG.debug("Server started on {}", baseServerUri);
    }

    public static String toSafeAddr(InetSocketAddress addr)
    {
        if (addr == null)
        {
            return "<null>";
        }
        return String.format("%s:%d", addr.getAddress().getHostAddress(), addr.getPort());
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterAll
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    @Test
    public void testEmptyConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/empty");

        BlockheadClientRequest request = client.newWsRequest(uri);
        request.header(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, "identity");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            HttpFields responseHeaders = clientConn.getUpgradeResponseHeaders();
            HttpField extensionHeader = responseHeaders.getField(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
            assertThat("response.extensions", extensionHeader.getValue(), is("identity"));
        }
    }

    @Test
    public void testNoExtensionsConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/no-extensions");

        BlockheadClientRequest request = client.newWsRequest(uri);
        request.header(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, "identity");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            HttpFields responseHeaders = clientConn.getUpgradeResponseHeaders();
            HttpField extensionHeader = responseHeaders.getField(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
            assertThat("response.extensions", extensionHeader, is(nullValue()));

            clientConn.write(new TextFrame().setPayload("NegoExts"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("negotiatedExtensions=[]"));
        }
    }

    @Test
    public void testCaptureRequestHeadersConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/capture-request-headers");

        BlockheadClientRequest request = client.newWsRequest(uri);
        request.header("X-Dummy", "Bogus");

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("X-Dummy"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Request Header [X-Dummy]: \"Bogus\""));
        }
    }

    @Test
    public void testUniqueUserPropsConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/unique-user-props");

        // First request
        BlockheadClientRequest request = client.newWsRequest(uri);
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("apple"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested User Property: [apple] = \"fruit from tree\""));
        }

        // Second request

        request = client.newWsRequest(uri);
        connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("apple"));
            clientConn.write(new TextFrame().setPayload("blueberry"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            // should have no value
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested User Property: [apple] = <null>"));

            frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested User Property: [blueberry] = \"fruit from bush\""));
        }
    }

    @Test
    public void testUserPropsAddress() throws Exception
    {
        URI uri = baseServerUri.resolve("/addr");

        BlockheadClientRequest request = client.newWsRequest(uri);
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            InetSocketAddress expectedLocal = clientConn.getLocalSocketAddress();
            InetSocketAddress expectedRemote = clientConn.getRemoteSocketAddress();

            clientConn.write(new TextFrame().setPayload("addr"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);

            StringWriter expected = new StringWriter();
            PrintWriter out = new PrintWriter(expected);
            // local <-> remote are opposite on server (duh)
            out.printf("[javax.websocket.endpoint.localAddress] = %s%n", toSafeAddr(expectedRemote));
            out.printf("[javax.websocket.endpoint.remoteAddress] = %s%n", toSafeAddr(expectedLocal));
            out.printf("[found.local] = %s%n", toSafeAddr(expectedRemote));
            out.printf("[found.remote] = %s%n", toSafeAddr(expectedLocal));

            assertThat("Frame Response", frame.getPayloadAsUTF8(), is(expected.toString()));
        }
    }

    /**
     * Test of Sec-WebSocket-Protocol, as seen in RFC-6455, 1 protocol
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocolSingle() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        BlockheadClientRequest request = client.newWsRequest(uri);
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "echo");
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("getProtocols"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\"]"));
        }
    }

    /**
     * Test of Sec-WebSocket-Protocol, as seen in RFC-6455, 3 protocols
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocolTriple() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        BlockheadClientRequest request = client.newWsRequest(uri);
        request.header(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "echo, chat, status");
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("getProtocols"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        }
    }

    /**
     * Test of Sec-WebSocket-Protocol, using all lowercase header
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocolLowercaseHeader() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        BlockheadClientRequest request = client.newWsRequest(uri);
        request.header("sec-websocket-protocol", "echo, chat, status");
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("getProtocols"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        }
    }

    /**
     * Test of Sec-WebSocket-Protocol, using non-spec case header
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocolAltHeaderCase() throws Exception
    {
        URI uri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);

        BlockheadClientRequest request = client.newWsRequest(uri);
        // We see "Websocket" (no capital "S" often)
        request.header("Sec-Websocket-Protocol", "echo, chat, status");
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("getProtocols"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        }
    }

    /**
     * Test of Sec-WebSocket-Protocol, using non-spec case header
     */
    @Test
    public void testDecoderWithProtocol() throws Exception
    {
        URI uri = baseServerUri.resolve("/timedecoder");

        BlockheadClientRequest request = client.newWsRequest(uri);
        // We see "Websocket" (no capital "S" often)
        request.header("SeC-WeBsOcKeT-PrOtOcOl", "gmt");
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("2016-06-20T14:27:44"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("cal=2016.06.20 AD at 14:27:44 +0000"));
        }
    }

    /**
     * Test that a Configurator declared in the annotation is used
     */
    @Test
    public void testAnnotationConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/config-normal");
        BlockheadClientRequest request = client.newWsRequest(uri);
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("tellme"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("UserProperties[self.configurator] = " + ConfigNormalConfigurator.class.getName()));
        }
    }

    /**
     * Test that a provided ServerEndpointConfig can override the annotation Configurator
     */
    @Test
    public void testOverrideConfigurator() throws Exception
    {
        URI uri = baseServerUri.resolve("/config-override");
        BlockheadClientRequest request = client.newWsRequest(uri);
        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            clientConn.write(new TextFrame().setPayload("tellme"));
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame frame = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Frame Response", frame.getPayloadAsUTF8(), is("UserProperties[self.configurator] = " + ConfigOverrideConfigurator.class.getName()));
        }
    }
}
