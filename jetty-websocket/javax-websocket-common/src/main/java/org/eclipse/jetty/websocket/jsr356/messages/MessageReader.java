//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.jsr356.MessageSink;

/**
 * Support class for reading a (single) WebSocket TEXT message via a Reader.
 * <p>
 * In compliance to the WebSocket spec, this reader always uses the {@link StandardCharsets#UTF_8}.
 */
public class MessageReader extends InputStreamReader implements MessageSink
{
    private final MessageInputStream stream;

    public MessageReader(MessageInputStream stream)
    {
        super(stream, StandardCharsets.UTF_8);
        this.stream = stream;
    }
    
    @Override
    public void accept(Frame frame, Callback callback)
    {
        this.stream.accept(frame, callback);
    }
}
