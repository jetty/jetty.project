package org.eclipse.jetty.websocket.core.proxy;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler.CoreSession;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ProxyFrameHandlerTest
{
    Server _server;
    WebSocketCoreClient _client;

    ProxyFrameHandler proxyFrameHandler;
    BasicFrameHandler.ServerEchoHandler serverFrameHandler;

    @BeforeEach
    public void start() throws Exception
    {
        _server = new Server();
        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(8080);
        _server.addConnector(connector);

        HandlerList handlers = new HandlerList();

        ContextHandler serverContext = new ContextHandler("/server");
        serverFrameHandler = new BasicFrameHandler.ServerEchoHandler("SERVER");
        WebSocketNegotiator negotiator = WebSocketNegotiator.from((negotiation) -> serverFrameHandler);
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(negotiator);
        serverContext.setHandler(upgradeHandler);
        handlers.addHandler(serverContext);

        _client = new WebSocketCoreClient();
        _client.start();
        URI uri = new URI("ws://localhost:8080/server");

        ContextHandler proxyContext = new ContextHandler("/proxy");
        proxyFrameHandler = new ProxyFrameHandler(_client, uri);
        negotiator = WebSocketNegotiator.from((negotiation) -> proxyFrameHandler);
        upgradeHandler = new WebSocketUpgradeHandler(negotiator);
        proxyContext.setHandler(upgradeHandler);
        handlers.addHandler(proxyContext);

        _server.setHandler(handlers);
        _server.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testHello() throws Exception
    {
        BasicFrameHandler clientHandler = new BasicFrameHandler("CLIENT");
        ClientUpgradeRequest upgradeRequest = ClientUpgradeRequest.from(_client, new URI("ws://localhost:8080/proxy"), clientHandler);

        CompletableFuture<CoreSession> response = _client.connect(upgradeRequest);
        response.get(5, TimeUnit.SECONDS);
        clientHandler.sendText("hello world");
        clientHandler.close("standard close");

        Frame frame;

        // Verify the the text frame was received
        assertThat(proxyFrameHandler.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));
        assertThat(serverFrameHandler.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));
        assertThat(proxyFrameHandler.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));
        assertThat(clientHandler.receivedFrames.poll().getPayloadAsUTF8(), is("hello world"));

        // Verify the right close frame was received
        assertThat(CloseStatus.getCloseStatus(proxyFrameHandler.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(serverFrameHandler.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(proxyFrameHandler.receivedFrames.poll()).getReason(), is("standard close"));
        assertThat(CloseStatus.getCloseStatus(clientHandler.receivedFrames.poll()).getReason(), is("standard close"));

        // Verify no other frames were received
        assertNull(proxyFrameHandler.receivedFrames.poll(250, TimeUnit.MILLISECONDS));
        assertNull(serverFrameHandler.receivedFrames.poll(250, TimeUnit.MILLISECONDS));
        assertNull(proxyFrameHandler.receivedFrames.poll(250, TimeUnit.MILLISECONDS));
        assertNull(clientHandler.receivedFrames.poll(250, TimeUnit.MILLISECONDS));
    }
}
