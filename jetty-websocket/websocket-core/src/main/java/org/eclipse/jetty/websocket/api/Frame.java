package org.eclipse.jetty.websocket.api;

/**
 * The immutable frame details.
 */
public interface Frame
{
    public byte[] getMask();

    public int getOpCode();

    public byte[] getPayload();

    public long getPayloadLength();

    public boolean isFin();

    public boolean isMasked();

    public boolean isRsv1();

    public boolean isRsv2();

    public boolean isRsv3();
}
