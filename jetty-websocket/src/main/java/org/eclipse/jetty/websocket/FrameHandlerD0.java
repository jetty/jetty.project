// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.websocket;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;

final class FrameHandlerD0 implements WebSocketParser.FrameHandler
{
    final WebSocket _websocket;
    final Utf8StringBuilder _utf8 = new Utf8StringBuilder();

    FrameHandlerD0(WebSocket websocket)
    {
        _websocket=websocket;
    }
    
    public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
    {
        assert more==false;
        try
        {
            byte[] array=buffer.array();
            
            if (opcode==0)
            {
                _websocket.onMessage(opcode,buffer.toString("utf-8"));
            }
            else
            {
                _websocket.onMessage(opcode,array,buffer.getIndex(),buffer.length());
            }
        }
        catch(ThreadDeath th)
        {
            throw th;
        }
        catch(Throwable th)
        {
            Log.warn(th);
        }
    }
}