package org.eclipse.jetty.websocket.io.payload;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * Used to perform validation of UTF8 payload contents (for fast-fail reasons)
 */
public class UTF8Validator extends Utf8Appendable implements PayloadProcessor
{
    private static class EmptyAppender implements Appendable
    {
        private int length = 0;

        @Override
        public Appendable append(char c) throws IOException
        {
            length++;
            return this;
        }

        @Override
        public Appendable append(CharSequence csq) throws IOException
        {
            length += csq.length();
            return this;
        }

        @Override
        public Appendable append(CharSequence csq, int start, int end) throws IOException
        {
            length += (end - start);
            return this;
        }

        public int getLength()
        {
            return length;
        }
    }

    private static final Logger LOG = Log.getLogger(UTF8Validator.class);

    private EmptyAppender buffer;

    public UTF8Validator()
    {
        super(new EmptyAppender());
        this.buffer = (EmptyAppender)_appendable;
    }

    @Override
    public int length()
    {
        return this.buffer.getLength();
    }

    @Override
    public void process(ByteBuffer payload)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Payload: {}",BufferUtil.toDetailString(payload));
        }

        if ((payload == null) || (payload.remaining() <= 0))
        {
            return;
        }

        try
        {
            append(payload.slice());
        }
        catch (NotUtf8Exception e)
        {
            throw new BadPayloadException(e);
        }
    }

    @Override
    public void reset(WebSocketFrame frame)
    {
        /* do nothing */
    }
}
