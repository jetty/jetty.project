package org.eclipse.jetty.websocket.io.message;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Appender for messages (used for multiple fragments with continuations, and also to allow for streaming APIs)
 */
public interface MessageAppender
{
    /**
     * Append the payload to the message.
     * 
     * @param payload
     *            the payload to append.
     * @throws IOException
     *             if unable to append the payload
     */
    abstract void appendMessage(ByteBuffer payload) throws IOException;

    /**
     * Notification that message is to be considered complete.
     */
    abstract void messageComplete();
}
