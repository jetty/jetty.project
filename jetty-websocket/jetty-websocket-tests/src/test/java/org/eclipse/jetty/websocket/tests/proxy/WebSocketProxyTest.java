package org.eclipse.jetty.websocket.tests.proxy;

import java.net.URI;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class WebSocketProxyTest
{
    private Server server;
    private URI serverUri;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080); // TODO: remove...
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        WebSocketUpgradeFilter.configure(contextHandler);
        NativeWebSocketServletContainerInitializer.configure(contextHandler, ((context, container) ->
        {
            container.addMapping("/*", (req, resp) -> new WebSocketProxy().getWebSocketConnectionListener());
        }));

        server.setHandler(contextHandler);
        server.start();
        serverUri = URI.create("ws://localhost:" + connector.getLocalPort());
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
    }

    @Test
    public void test() throws Exception
    {
        server.join();
    }
}
