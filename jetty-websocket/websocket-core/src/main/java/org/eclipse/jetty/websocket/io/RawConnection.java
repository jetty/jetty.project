package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;

/**
 * For advanced usage of connections.
 */
public interface RawConnection
{
    ByteBufferPool getBufferPool();

    Executor getExecutor();

    <C> void writeRaw(C context, Callback<C> callback, ByteBuffer... buf) throws IOException;
}
