//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.util.function.Function;

import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.common.message.PartialTextMessage;
import org.eclipse.jetty.websocket.common.message.PartialTextMessageSink;

/**
 * Partial TEXT MessageAppender for &#064;{@link OnMessage} annotated methods
 * @deprecated should just use PartialTextMessageSink directly
 */
@Deprecated
public class TextPartialOnMessage extends PartialTextMessageSink
{
    public TextPartialOnMessage(Function< PartialTextMessage, Void> function) {
        super(function);
    }

    /*@Override
    public void appendFrame(ByteBuffer payload, boolean isLast) throws IOException
    {
        if (finished)
        {
            throw new IOException("Cannot append to finished buffer");
        }
        
        String text = utf8Partial.toPartialString(payload);
        driver.onPartialTextMessage(text,isLast);
    }

    @Override
    public void messageComplete()
    {
        finished = true;
    }*/
}
