package org.eclipse.jetty.websocket.server.ab;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.server.SimpleServletServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public abstract class AbstractABCase
{
    protected static final byte FIN = (byte)0x80;
    protected static final byte NOFIN = 0x00;
    private static final byte MASKED_BIT = (byte)0x80;
    private static final byte[] MASK =
    { 0x12, 0x34, 0x56, 0x78 };

    protected static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new ABServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    protected byte[] masked(final byte[] data)
    {
        int len = data.length;
        byte ret[] = new byte[len];
        System.arraycopy(data,0,ret,0,len);
        for (int i = 0; i < len; i++)
        {
            ret[i] ^= MASK[i % 4];
        }
        return ret;
    }

    private void putLength(ByteBuffer buf, int length, boolean masked)
    {
        if (length < 0)
        {
            throw new IllegalArgumentException("Length cannot be negative");
        }
        byte b = (masked?MASKED_BIT:0x00);

        // write the uncompressed length
        if (length > 0xFF_FF)
        {
            buf.put((byte)(b | 0x7F));
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)((length >> 24) & 0xFF));
            buf.put((byte)((length >> 16) & 0xFF));
            buf.put((byte)((length >> 8) & 0xFF));
            buf.put((byte)(length & 0xFF));
        }
        else if (length >= 0x7E)
        {
            buf.put((byte)(b | 0x7E));
            buf.put((byte)(length >> 8));
            buf.put((byte)(length & 0xFF));
        }
        else
        {
            buf.put((byte)(b | length));
        }
    }

    public void putMask(ByteBuffer buf)
    {
        buf.put(MASK);
    }

    public void putPayloadLength(ByteBuffer buf, int length)
    {
        putLength(buf,length,true);
    }
}
