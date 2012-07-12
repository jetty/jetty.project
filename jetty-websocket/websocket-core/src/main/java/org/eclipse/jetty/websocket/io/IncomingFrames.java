package org.eclipse.jetty.websocket.io;

import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Interface for dealing with Incoming Frames.
 */
public interface IncomingFrames
{
    public void incoming(WebSocketException e);

    public void incoming(WebSocketFrame frame);
}
