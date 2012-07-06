package org.eclipse.jetty.websocket.protocol;

import org.junit.Assert;
import org.junit.Test;

public class FrameBuilderTest
{
    @Test
    public void testSimpleInvalidCloseFrameBuilder()
    {
        byte[] actual = FrameBuilder.close().fin(false).asByteArray();

        byte[] expected = new byte[]
                { (byte)0x08, (byte)0x00 };

        Assert.assertArrayEquals(expected,actual);
    }

    @Test
    public void testSimpleInvalidPingFrameBuilder()
    {
        byte[] actual = FrameBuilder.ping().fin(false).asByteArray();

        byte[] expected = new byte[]
                { (byte)0x09, (byte)0x00 };

        Assert.assertArrayEquals(expected,actual);
    }

    @Test
    public void testSimpleValidCloseFrame()
    {
        byte[] actual = FrameBuilder.close(1000).asByteArray();

        byte[] expected = new byte[]
                { (byte)0x88, (byte)0x02, (byte)0x03, (byte)0xe8 };

        Assert.assertArrayEquals(expected,actual);
    }

    @Test
    public void testSimpleValidPingFrame()
    {
        byte[] actual = FrameBuilder.ping().asByteArray();

        byte[] expected = new byte[]
                { (byte)0x89, (byte)0x00 };

        Assert.assertArrayEquals(expected,actual);
    }
}
