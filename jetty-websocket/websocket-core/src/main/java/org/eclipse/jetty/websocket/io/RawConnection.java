package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;

/**
 * Interface for working with connections in a raw way.
 * <p>
 * This is abstracted out to allow for common access to connection internals regardless of physical vs virtual connections.
 */
public interface RawConnection
{
    void close() throws IOException;

    ByteBufferPool getBufferPool();

    Executor getExecutor();

    Generator getGenerator();

    Parser getParser();

    WebSocketPolicy getPolicy();

    FrameQueue getQueue();
}
