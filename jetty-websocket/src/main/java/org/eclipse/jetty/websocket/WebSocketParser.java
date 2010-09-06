package org.eclipse.jetty.websocket;

import org.eclipse.jetty.io.Buffer;



/* ------------------------------------------------------------ */
/**
 * Parser the WebSocket protocol.
 *
 */
public interface WebSocketParser
{
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface FrameHandler
    {
        void onFrame(boolean more,byte flags, byte opcode, Buffer buffer);
    }

    Buffer getBuffer();

    int parseNext();

    boolean isBufferEmpty();

    void fill(Buffer buffer);

}
