package org.eclipse.jetty.websocket.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.protocol.OpCode;

/**
 * Validate UTF8 correctness for {@link OpCode#CLOSE} Reason message.
 */
public class CloseReasonValidator extends UTF8Validator implements PayloadProcessor
{
    private int statusCodeBytes = 2;

    @Override
    public void process(ByteBuffer payload)
    {
        if ((payload == null) || (payload.remaining() <= 2))
        {
            // no validation needed
            return;
        }

        ByteBuffer copy = payload.slice();
        while (statusCodeBytes > 0)
        {
            copy.get();
            statusCodeBytes--;
        }

        super.process(copy);
    }
}
