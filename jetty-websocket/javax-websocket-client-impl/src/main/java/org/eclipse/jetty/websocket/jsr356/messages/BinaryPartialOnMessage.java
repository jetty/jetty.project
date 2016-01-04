//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.messages;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.websocket.OnMessage;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.common.message.MessageAppender;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrAnnotatedEventDriver;

/**
 * Partial BINARY MessageAppender for &#064;{@link OnMessage} annotated methods
 */
public class BinaryPartialOnMessage implements MessageAppender
{
    private final JsrAnnotatedEventDriver driver;
    private boolean finished;

    public BinaryPartialOnMessage(JsrAnnotatedEventDriver driver)
    {
        this.driver = driver;
        this.finished = false;
    }

    @Override
    public void appendFrame(ByteBuffer payload, boolean isLast) throws IOException
    {
        if (finished)
        {
            throw new IOException("Cannot append to finished buffer");
        }
        if (payload == null)
        {
            driver.onPartialBinaryMessage(BufferUtil.EMPTY_BUFFER,isLast);
        }
        else
        {
            driver.onPartialBinaryMessage(payload,isLast);
        }
    }

    @Override
    public void messageComplete()
    {
        finished = true;
    }
}
