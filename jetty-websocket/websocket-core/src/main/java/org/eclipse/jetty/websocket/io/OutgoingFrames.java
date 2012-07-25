package org.eclipse.jetty.websocket.io;

import java.io.IOException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Interface for dealing with outgoing frames.
 */
public interface OutgoingFrames
{
    <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws IOException;
}
