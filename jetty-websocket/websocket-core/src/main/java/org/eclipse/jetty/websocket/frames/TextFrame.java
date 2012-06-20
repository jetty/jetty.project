package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.api.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.6">Text Data Frame (0x01)</a>.
 */
public class TextFrame extends DataFrame
{
    private StringBuilder data = new StringBuilder();

    /**
     * Default constructor (unspecified data)
     */
    public TextFrame()
    {
        super(OpCode.TEXT);
    }

    /**
     * Get the data
     * 
     * @return the raw StringBuilder data (can be null)
     */
    public StringBuilder getData()
    {
        return data;
    }

    /**
     * Set the data and payload length.
     * 
     * @param str
     *            the String to set
     */
    public void setData(String str)
    {
        int len = str.length();
        this.data = new StringBuilder(str);
        this.setPayloadLength(len);
    }

    /**
     * Set the data and payload length.
     * 
     * @param str
     *            the StringBuilder to set
     */
    public void setData(StringBuilder str)
    {
        int len = str.length();
        this.data = str;
        this.setPayloadLength(len);
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("TextFrame[");
        b.append("len=").append(getPayloadLength());
        b.append(",data=").append(data);
        b.append("]");
        return b.toString();
    }
}
