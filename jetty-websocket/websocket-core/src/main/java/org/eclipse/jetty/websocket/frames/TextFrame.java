package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.6">Text Data Frame (0x01)</a>.
 */
public class TextFrame extends DataFrame
{
    /**
     * Default constructor (unspecified data)
     */
    public TextFrame()
    {
        super(OpCode.TEXT);
    }

    /**
     * Construct text frame with message data
     * 
     * @param message
     *            the message data
     */
    public TextFrame(String message)
    {
        this();
        setPayload(message);
    }

    /**
     * Set the data and payload length.
     * 
     * @param str
     *            the String to set
     */
    public void setPayload(String str)
    {
        int len = str.length();
        ByteBuffer b = ByteBuffer.allocate(len);
        b.put(str.getBytes()); // TODO validate utf-8
        this.setPayload(b);
        this.setPayloadLength(len);
    }
    
    public String getPayloadAsText()
    {
        return new String(getPayload().array());
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("TextFrame[");
        b.append("len=").append(getPayloadLength());
        b.append(",data=").append(getPayload()); // TODO pp
        b.append("]");
        return b.toString();
    }
}
