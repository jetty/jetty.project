package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.protocol.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.6">Text Data Frame (0x01)</a>.
 * <p>
 * Note: UTF8 is the only charset supported for Text Frames according to RFC 6455.
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
        setFin(true); // assume a final message with this constructor
        setPayload(message);
    }

    /**
     * Get the Payload as a UTF8 charset string.
     * <p>
     * Note: UTF8 is the only charset supported for Text Frames according to RFC 6455.
     * 
     * @return a UTF8 format String representation of the payload
     */
    public String getPayloadUTF8()
    {
        return BufferUtil.toUTF8String(getPayload());
    }

    /**
     * Set the data and payload length.
     * 
     * @param message
     *            the String to set
     */
    public void setPayload(String message)
    {
        byte utf[] = message.getBytes(StringUtil.__UTF8_CHARSET);
        super.setPayload(utf);
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("TextFrame[");
        b.append("len=").append(getPayloadLength());
        b.append(",data=").append(getPayloadUTF8());
        b.append("]");
        return b.toString();
    }
}
