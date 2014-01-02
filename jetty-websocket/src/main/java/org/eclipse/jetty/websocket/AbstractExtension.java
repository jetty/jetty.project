//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.websocket.WebSocketParser.FrameHandler;

public class AbstractExtension implements Extension
{
    private static final int[] __mask = { -1, 0x04, 0x02, 0x01};
    private final String _name;
    private final Map<String,String> _parameters=new HashMap<String, String>();
    private FrameHandler _inbound;
    private WebSocketGenerator _outbound;
    private WebSocket.FrameConnection _connection;
    
    public AbstractExtension(String name)
    {
        _name = name;
    }
    
    public WebSocket.FrameConnection getConnection()
    {
        return _connection;
    }

    public boolean init(Map<String, String> parameters)
    {
        _parameters.putAll(parameters);
        return true;
    }
    
    public String getInitParameter(String name)
    {
        return _parameters.get(name);
    }

    public String getInitParameter(String name,String dft)
    {
        if (!_parameters.containsKey(name))
            return dft;
        return _parameters.get(name);
    }

    public int getInitParameter(String name, int dft)
    {
        String v=_parameters.get(name);
        if (v==null)
            return dft;
        return Integer.valueOf(v);
    }
    
    
    public void bind(WebSocket.FrameConnection connection, FrameHandler incoming, WebSocketGenerator outgoing)
    {
        _connection=connection;
        _inbound=incoming;
        _outbound=outgoing;
    }

    public String getName()
    {
        return _name;
    }

    public String getParameterizedName()
    {
        StringBuilder name = new StringBuilder();
        name.append(_name);
        for (String param : _parameters.keySet())
            name.append(';').append(param).append('=').append(QuotedStringTokenizer.quoteIfNeeded(_parameters.get(param),";="));
        return name.toString();
    }

    public void onFrame(byte flags, byte opcode, Buffer buffer)
    {
        // System.err.printf("onFrame %s %x %x %d\n",getExtensionName(),flags,opcode,buffer.length());
        _inbound.onFrame(flags,opcode,buffer);
    }

    public void close(int code, String message)
    {
        _inbound.close(code,message);
    }

    public int flush() throws IOException
    {
        return _outbound.flush();
    }

    public boolean isBufferEmpty()
    {
        return _outbound.isBufferEmpty();
    }

    public void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        // System.err.printf("addFrame %s %x %x %d\n",getExtensionName(),flags,opcode,length);
        _outbound.addFrame(flags,opcode,content,offset,length);
    }
    
    public byte setFlag(byte flags,int rsv)
    {
        if (rsv<1||rsv>3)
            throw new IllegalArgumentException("rsv"+rsv);
        byte b=(byte)(flags | __mask[rsv]);
        return b;
    }
    
    public byte clearFlag(byte flags,int rsv)
    {
        if (rsv<1||rsv>3)
            throw new IllegalArgumentException("rsv"+rsv);
        return (byte)(flags & ~__mask[rsv]);
    }

    public boolean isFlag(byte flags,int rsv)
    {
        if (rsv<1||rsv>3)
            throw new IllegalArgumentException("rsv"+rsv);
        return (flags & __mask[rsv])!=0;
    }
    
    public String toString()
    {
        return getParameterizedName();
    }
}
