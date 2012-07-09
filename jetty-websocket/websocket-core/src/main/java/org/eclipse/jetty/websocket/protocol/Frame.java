package org.eclipse.jetty.websocket.protocol;

/**
 * The immutable frame details.
 * <p>
 * Used by end user via @OnWebSocketFrame
 */
public interface Frame
{
    public byte[] getMask();

    public OpCode getOpCode();

    /**
     * @return a copy of the payload data
     */
    public byte[] getPayloadData();

    public int getPayloadLength();

    public boolean isFin();

    public boolean isMasked();

    public boolean isRsv1();

    public boolean isRsv2();

    public boolean isRsv3();
}
