package org.eclipse.jetty.websocket.core.proxy;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.websocket.core.FrameHandler.CoreSession;
import org.eclipse.jetty.websocket.core.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class WebSocketProxyTest
{
    Server _server;
    WebSocketCoreClient _client;


    @BeforeEach
    public void start() throws Exception
    {
        _server = new Server();
        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(8080);
        _server.addConnector(connector);

        HandlerList handlers = new HandlerList();

        ContextHandler serverContext = new ContextHandler("/server");
        WebSocketNegotiator negotiator = WebSocketNegotiator.from((negotiation) -> new BasicFrameHandler.EchoHandler("SERVER"));
        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(negotiator);
        serverContext.setHandler(upgradeHandler);
        handlers.addHandler(serverContext);

        ContextHandler proxyContext = new ContextHandler("/proxy");
        negotiator = WebSocketNegotiator.from((negotiation) -> new ProxyFrameHandler());
        upgradeHandler = new WebSocketUpgradeHandler(negotiator);
        proxyContext.setHandler(upgradeHandler);
        handlers.addHandler(proxyContext);

        _server.setHandler(handlers);
        _server.start();

        _client = new WebSocketCoreClient();
        _client.start();
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
        clientHandler.close();
    }
}
