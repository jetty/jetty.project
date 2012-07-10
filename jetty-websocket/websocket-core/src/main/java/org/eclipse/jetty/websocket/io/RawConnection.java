package org.eclipse.jetty.websocket.io;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;

/**
 * Interface for working with connections in a raw way.
 * <p>
 * This is abstracted out to allow for common access to connection internals regardless of physical vs virtual connections.
 */
public interface RawConnection extends WebSocketConnection
{
    <C> void complete(FrameBytes<C> frameBytes);

    void flush();

    ByteBufferPool getBufferPool();

    Executor getExecutor();

    Generator getGenerator();

    Parser getParser();

    FrameQueue getQueue();
}
