//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.common.events.AnnotatedEventDriver;

/**
 * Support class for reading binary message data as an InputStream.
 */
public class MessageInputStream extends InputStream implements MessageAppender
{
    private static final int BUFFER_SIZE = 65535;
    /**
     * Threshold (of bytes) to perform compaction at
     */
    private static final int COMPACT_THRESHOLD = 5;
    private final AnnotatedEventDriver driver;
    private final ByteBuffer buf;
    private int size;
    private boolean finished;
    private boolean needsNotification;
    private int readPosition;

    public MessageInputStream(AnnotatedEventDriver driver)
    {
        this.driver = driver;
        this.buf = ByteBuffer.allocate(BUFFER_SIZE);
        BufferUtil.clearToFill(this.buf);
        size = 0;
        readPosition = this.buf.position();
        finished = false;
        needsNotification = true;
    }

    @Override
    public void appendMessage(ByteBuffer payload) throws IOException
    {
        if (finished)
        {
            throw new IOException("Cannot append to finished buffer");
        }

        if (payload == null)
        {
            // empty payload is valid
            return;
        }

        driver.getPolicy().assertValidBinaryMessageSize(size + payload.remaining());
        size += payload.remaining();

        synchronized (buf)
        {
            // TODO: grow buffer till max binary message size?
            // TODO: compact this buffer to fit incoming buffer?
            // TODO: tell connection to suspend if buffer too full?
            BufferUtil.put(payload,buf);
        }

        if (needsNotification)
        {
            needsNotification = true;
            this.driver.onInputStream(this);
        }
    }

    @Override
    public void close() throws IOException
    {
        finished = true;
        super.close();
    }

    @Override
    public void messageComplete()
    {
        finished = true;
    }

    @Override
    public int read() throws IOException
    {
        synchronized (buf)
        {
            byte b = buf.get(readPosition);
            readPosition++;
            if (readPosition <= (buf.limit() - COMPACT_THRESHOLD))
            {
                int curPos = buf.position();
                buf.compact();
                int offsetPos = buf.position() - curPos;
                readPosition += offsetPos;
            }
            return b;
        }
    }
}
