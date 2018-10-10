package org.eclipse.jetty.websocket.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.server.internal.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.server.internal.UpgradeResponseAdapter;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFrameHandlerFactory;

public class JettyWebSocketServletFrameHandlerFactory extends JettyWebSocketFrameHandlerFactory implements WebSocketServletFrameHandlerFactory
{
    public JettyWebSocketServletFrameHandlerFactory(Executor executor)
    {
        super(executor);
    }

    @Override
    public FrameHandler newFrameHandler(Object websocketPojo, ServletUpgradeRequest upgradeRequest, ServletUpgradeResponse upgradeResponse)
    {
        return super.newJettyFrameHandler(websocketPojo, new UpgradeRequestAdapter(upgradeRequest), new UpgradeResponseAdapter(upgradeResponse), new CompletableFuture<>());
    }
}
