package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

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

    public static void assertEquals(String message, String expectedString, ByteBuffer actualBuffer)
    {
        int len = actualBuffer.remaining();
        byte actual[] = new byte[len];
        actualBuffer.get(actual, 0, len);

        byte expected[] = expectedString.getBytes();
        assertEquals(message, expected, actual);
    }
}
