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

final class FrameHandlerD1 implements WebSocketParser.FrameHandler
{
    public final static byte PING=1;
    public final static byte PONG=1;

    final WebSocketConnectionD00 _connection;
    final WebSocket _websocket;
    final Utf8StringBuilder _utf8 = new Utf8StringBuilder();
    boolean _fragmented=false;

    FrameHandlerD1(WebSocketConnectionD00 connection, WebSocket websocket)
    {
        _connection=connection;
        _websocket=websocket;
    }
    
    public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
    {
        try
        {
            byte[] array=buffer.array();
            
            if (opcode==0)
            {
                if (more)
                {
                    _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
                    _fragmented=true;
                }
                else if (_fragmented)
                {
                    _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
                    _websocket.onMessage(opcode,_utf8.toString());
                    _utf8.reset();
                    _fragmented=false;
                }
                else
                {
                    _websocket.onMessage(opcode,buffer.toString("utf-8"));
                }
            }
            else if (opcode==PING)
            {
                _connection.sendMessage(PONG,buffer.array(),buffer.getIndex(),buffer.length());
            }
            else if (opcode==PONG)
            {
                
            }
            else
            {
                if (more)
                {
                    _websocket.onFragment(true,opcode,array,buffer.getIndex(),buffer.length());
                }
                else if (_fragmented)
                {
                    _websocket.onFragment(false,opcode,array,buffer.getIndex(),buffer.length());
                }
                else
                {
                    _websocket.onMessage(opcode,array,buffer.getIndex(),buffer.length());
                }
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