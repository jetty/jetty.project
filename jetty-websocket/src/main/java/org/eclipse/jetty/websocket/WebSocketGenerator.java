package org.eclipse.jetty.websocket;

import java.io.IOException;



/* ------------------------------------------------------------ */
/** WebSocketGenerator.
 */
public interface WebSocketGenerator
{
    int flush() throws IOException;
    boolean isBufferEmpty();
    void addFrame(byte opcode, String content, int maxIdleTime) throws IOException;
    void addFrame(byte opcode, byte[] content, int offset, int length, int maxIdleTime) throws IOException;
    void addFrame(byte opcode, byte[] content, int maxIdleTime)throws IOException;
    int flush(int maxIdleTime) throws IOException;
}
