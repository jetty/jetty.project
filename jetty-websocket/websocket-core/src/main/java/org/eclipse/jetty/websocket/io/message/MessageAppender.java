package org.eclipse.jetty.websocket.io.message;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface MessageAppender
{
    abstract void appendMessage(ByteBuffer byteBuffer) throws IOException;

    abstract void messageComplete() throws IOException;
}
