package org.eclipse.jetty.websocket.io;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Interface for working with connections in a raw way.
 * <p>
 * This is abstracted out to allow for common access to connection internals regardless of physical vs virtual connections.
 */
public interface RawConnection extends WebSocketConnection
{
    <C> void complete(FrameBytes<C> frameBytes);

    void disconnect(boolean onlyOutput);

    void flush();

    ByteBufferPool getBufferPool();

    Executor getExecutor();

    Generator getGenerator();

    Parser getParser();

    FrameQueue getQueue();

    <C> void write(C context, Callback<C> callback, WebSocketFrame frame);
}
