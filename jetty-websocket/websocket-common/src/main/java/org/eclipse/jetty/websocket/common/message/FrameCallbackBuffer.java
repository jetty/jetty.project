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

package org.eclipse.jetty.websocket.common.message;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.FrameCallback;

public class FrameCallbackBuffer
{
    public ByteBuffer buffer;
    public FrameCallback callback;
    
    public FrameCallbackBuffer(FrameCallback callback, ByteBuffer buffer)
    {
        this.callback = callback;
        this.buffer = buffer;
    }
    
    @Override
    public String toString()
    {
        return String.format("FrameCallbackBuffer[%s,%s]", BufferUtil.toDetailString(buffer),callback.getClass().getSimpleName());
    }
}
