package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

public class MaskedByteBuffer
{
    private static byte[] mask = new byte[]
            { 0x00, (byte)0xF0, 0x0F, (byte)0xFF };

    public static void putMask(ByteBuffer buffer)
    {
        buffer.put(mask,0,mask.length);
    }

    public static void putPayload(ByteBuffer buffer, byte[] payload)
    {
        int len = payload.length;
        for (int i = 0; i < len; i++)
        {
            buffer.put((byte)(payload[i] ^ mask[i % 4]));
        }
    }

    public static void putPayload(ByteBuffer buffer, ByteBuffer payload)
    {
        int len = payload.remaining();
        for (int i = 0; i < len; i++)
        {
            buffer.put((byte)(payload.get() ^ mask[i % 4]));
        }
    }
}
