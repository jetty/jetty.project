package org.eclipse.jetty.websocket.frames;

import org.junit.Assert;
import org.junit.Test;

public class FrameBuilderTest
{
    public void testSimpleAsFrame()
    {
        PingFrame frame = (PingFrame)FrameBuilder.pingFrame().asFrame();

        Assert.assertTrue(frame instanceof PingFrame);
    }

    @Test
    public void testSimpleInvalidCloseFrameBuilder()
    {
        byte[] actual = FrameBuilder.closeFrame().isFin(false).asByteArray();

        byte[] expected = new byte[]
                { (byte)0x08, (byte)0x00 };

        Assert.assertArrayEquals(expected,actual);
    }

    @Test
    public void testSimpleInvalidPingFrameBuilder()
    {
        byte[] actual = FrameBuilder.pingFrame().isFin(false).asByteArray();

        byte[] expected = new byte[]
                { (byte)0x09, (byte)0x00 };

        Assert.assertArrayEquals(expected,actual);
    }

    @Test
    public void testSimpleValidCloseFrame()
    {
        byte[] actual = FrameBuilder.closeFrame().asByteArray();

        byte[] expected = new byte[]
                { (byte)0x88, (byte)0x00 };

        Assert.assertArrayEquals(expected,actual);
    }

    @Test
    public void testSimpleValidPingFrame()
    {
        byte[] actual = FrameBuilder.pingFrame().asByteArray();

        byte[] expected = new byte[]
                { (byte)0x89, (byte)0x00 };

        Assert.assertArrayEquals(expected,actual);
    }
}
