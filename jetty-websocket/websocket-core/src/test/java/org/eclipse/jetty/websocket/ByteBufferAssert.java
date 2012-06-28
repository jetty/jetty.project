package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;

public class ByteBufferAssert
{
    public static void assertEquals(String message, byte[] expected, byte[] actual)
    {
        Assert.assertThat(message + " byte[].length",actual.length,is(expected.length));
        int len = expected.length;
        for (int i = 0; i < len; i++)
        {
            Assert.assertThat(message + " byte[" + i + "]",actual[i],is(expected[i]));
        }
    }

    public static void assertEquals(String message, ByteBuffer expectedBuffer, ByteBuffer actualBuffer)
    {
        byte expectedBytes[] = BufferUtil.toArray(expectedBuffer);
        byte actualBytes[] = BufferUtil.toArray(actualBuffer);
        assertEquals(message,expectedBytes,actualBytes);
    }

    public static void assertEquals(String message, String expectedString, ByteBuffer actualBuffer)
    {
        String actualString = BufferUtil.toString(actualBuffer);
        Assert.assertThat(message,expectedString,is(actualString));
    }

    public static void assertSize(String message, int expectedSize, ByteBuffer buffer)
    {
        if ((expectedSize == 0) && (buffer == null))
        {
            return;
        }
        Assert.assertThat(message + " buffer.remaining",buffer.remaining(),is(expectedSize));
    }
}
