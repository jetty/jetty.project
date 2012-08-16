package org.eclipse.jetty.websocket.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.junit.Assert;
import org.junit.Test;

public class UTF8ValidatorTest
{
    private ByteBuffer asByteBuffer(String hexStr)
    {
        byte buf[] = TypeUtil.fromHexString(hexStr);
        return ByteBuffer.wrap(buf);
    }

    @Test
    public void testCase6_4_3()
    {
        ByteBuffer part1 = asByteBuffer("cebae1bdb9cf83cebcceb5"); // good
        ByteBuffer part2 = asByteBuffer("f4908080"); // INVALID
        ByteBuffer part3 = asByteBuffer("656469746564"); // good

        UTF8Validator validator = new UTF8Validator();
        validator.process(part1); // good
        try
        {
            validator.process(part2); // bad
            Assert.fail("Expected a " + BadPayloadException.class);
        }
        catch (BadPayloadException e)
        {
            // expected path
        }
        validator.process(part3); // good
    }
}
