package org.eclipse.jetty.websocket.server.examples.echo;

import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketRequest;
import org.eclipse.jetty.websocket.server.WebSocketResponse;

/**
 * Example of setting up a creator to create appropriately via the proposed and negotiated protocols.
 */
public class EchoCreator implements WebSocketCreator
{
    private BigEchoSocket bigEchoSocket = new BigEchoSocket();
    private EchoFragmentSocket echoFragmentSocket = new EchoFragmentSocket();
    private LogSocket logSocket = new LogSocket();

    @Override
    public Object createWebSocket(WebSocketRequest req, WebSocketResponse resp)
    {
        for (String protocol : req.getSubProtocols())
        {
            switch (protocol)
            {
                case "org.ietf.websocket.test-echo":
                case "echo":
                    resp.setAcceptedSubProtocol(protocol);
                    // TODO: how is this different than "echo-assemble"?
                    return bigEchoSocket;
                case "org.ietf.websocket.test-echo-broadcast":
                case "echo-broadcast":
                    resp.setAcceptedSubProtocol(protocol);
                    return new EchoBroadcastSocket();
                case "echo-broadcast-ping":
                    resp.setAcceptedSubProtocol(protocol);
                    return new EchoBroadcastPingSocket();
                case "org.ietf.websocket.test-echo-assemble":
                case "echo-assemble":
                    resp.setAcceptedSubProtocol(protocol);
                    // TODO: how is this different than "test-echo"?
                    return bigEchoSocket;
                case "org.ietf.websocket.test-echo-fragment":
                case "echo-fragment":
                    resp.setAcceptedSubProtocol(protocol);
                    return echoFragmentSocket;
                default:
                    return logSocket;
            }
        }
        return null;
    }
}