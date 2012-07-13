package org.eclipse.jetty.websocket.io;

import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Interface for dealing with outgoing frames.
 */
public interface OutgoingFrames
{
    void output(WebSocketFrame frame);
}
