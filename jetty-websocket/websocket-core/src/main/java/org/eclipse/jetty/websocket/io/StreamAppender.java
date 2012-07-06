package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface StreamAppender
{
    void appendBuffer(byte buf[]);

    void bufferComplete() throws IOException;

    ByteBuffer getBuffer();
}
