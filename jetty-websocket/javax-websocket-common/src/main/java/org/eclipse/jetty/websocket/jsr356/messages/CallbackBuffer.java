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

import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class CallbackBuffer
{
    public ByteBuffer buffer;
    public Callback callback;
    
    public CallbackBuffer(Callback callback, ByteBuffer buffer)
    {
        Objects.requireNonNull(buffer, "buffer");
        this.callback = callback;
        this.buffer = buffer;
    }
    
    @Override
    public String toString()
    {
        return String.format("CallbackBuffer[%s,%s]", BufferUtil.toDetailString(buffer),callback.getClass().getSimpleName());
    }
}
