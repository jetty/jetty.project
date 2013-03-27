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
import java.io.Reader;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.common.events.EventDriver;

/**
 * Support class for reading text message data as an Reader.
 * <p>
 * Due to the spec, this reader is forced to use the UTF8 charset.
 */
public class MessageReader extends Reader implements MessageAppender
{
    private final EventDriver driver;
    private final Utf8StringBuilder utf;
    private int size;
    private boolean finished;
    private boolean needsNotification;

    public MessageReader(EventDriver driver)
    {
        this.driver = driver;
        this.utf = new Utf8StringBuilder();
        size = 0;
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

        driver.getPolicy().assertValidTextMessageSize(size + payload.remaining());
        size += payload.remaining();

        synchronized (utf)
        {
            utf.append(payload);
        }

        if (needsNotification)
        {
            needsNotification = true;
            this.driver.onReader(this);
        }
    }

    @Override
    public void close() throws IOException
    {
        finished = true;
    }

    @Override
    public void messageComplete()
    {
        finished = true;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException
    {
        // TODO Auto-generated method stub
        return 0;
    }
}
