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

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;



/* ------------------------------------------------------------ */
/**
 * Parser the WebSocket protocol.
 *
 */
public class WebSocketParserD01 implements WebSocketParser
{    
    public enum State { 
        START(1), LENGTH_7(2), LENGTH_16(4), LENGTH_63(10), DATA(10);

        int _minSize;

        State(int minSize)
        {
            _minSize=minSize;
        }

        int getMinSize()
        {
            return _minSize;
        }
    };


    private final WebSocketBuffers _buffers;
    private final EndPoint _endp;
    private final FrameHandler _handler;
    private State _state=State.START;
    private Buffer _buffer;
    private boolean _more;
    private byte _flags;
    private byte _opcode;
    private int _count;
    private long _length;
    private Utf8StringBuilder _utf8;

    /* ------------------------------------------------------------ */
    /**
     * @param buffers The buffers to use for parsing.  Only the {@link Buffers#getBuffer()} is used.
     * This should be a direct buffer if binary data is mostly used or an indirect buffer if utf-8 data
     * is mostly used.
     * @param endp
     * @param handler
     */
    public WebSocketParserD01(WebSocketBuffers buffers, EndPoint endp, FrameHandler handler)
    {
        _buffers=buffers;
        _endp=endp;
        _handler=handler;
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferEmpty()
    {
        return _buffer==null || _buffer.length()==0;
    }

    /* ------------------------------------------------------------ */
    public Buffer getBuffer()
    {
        return _buffer;
    }

    /* ------------------------------------------------------------ */
    /** Parse to next event.
     * Parse to the next {@link FrameHandler} event or until no more data is
     * available. Fill data from the {@link EndPoint} only as necessary.
     * @return An indication of progress or otherwise. -1 indicates EOF, 0 indicates
     * that no bytes were read and no messages parsed. A positive number indicates either
     * the bytes filled or the messages parsed.
     */
    public int parseNext()
    {
        if (_buffer==null)
            _buffer=_buffers.getBuffer();

        int total_filled=0;

        // Loop until an datagram call back or can't fill anymore
        while(true)
        {
            int available=_buffer.length();

            // Fill buffer if we need a byte or need length
            if (available < _state.getMinSize() || _state==State.DATA && available<_count)
            {
                // compact to mark (set at start of data)
                _buffer.compact();

                // if no space, then the data is too big for buffer
                if (_buffer.space() == 0)
                    throw new IllegalStateException("FULL");

                // catch IOExceptions (probably EOF) and try to parse what we have
                try
                {
                    int filled=_endp.isOpen()?_endp.fill(_buffer):-1;
                    if (filled<=0)
                        return total_filled;
                    total_filled+=filled;
                    available=_buffer.length();
                }
                catch(IOException e)
                {
                    Log.debug(e);
                    return total_filled>0?total_filled:-1;
                }
            }

            // Parse the buffer byte by byte (unless it is STATE_DATA)
            byte b;
            while (_state!=State.DATA && available-->0)
            {
                switch (_state)
                {
                    case START:
                        b=_buffer.get();
                        _opcode=(byte)(b&0xf);
                        _flags=(byte)(b>>4);
                        _more=(_flags&8)!=0;
                        _state=State.LENGTH_7;
                        continue;

                    case LENGTH_7:
                        b=_buffer.get();
                        switch(b)
                        {
                            case 127:
                                _length=0;
                                _count=8;
                                _state=State.LENGTH_63;
                                break;
                            case 126:
                                _length=0;
                                _count=2;
                                _state=State.LENGTH_16;
                                break;
                            default:
                                _length=(0x7f&b);
                                _count=(int)_length;
                                _state=State.DATA; 
                        }
                        continue;

                    case LENGTH_16:
                        b=_buffer.get();
                        _length = _length<<8 | b;
                        if (--_count==0)
                        {
                            if (_length>=_buffer.capacity()-4)
                                throw new IllegalStateException("TOO LARGE");
                            _count=(int)_length;
                            _state=State.DATA;
                        }
                        continue;

                    case LENGTH_63:
                        b=_buffer.get();
                        _length = _length<<8 | b;
                        if (--_count==0)
                        {
                            if (_length>=_buffer.capacity()-10)
                                throw new IllegalStateException("TOO LARGE");
                            _count=(int)_length;
                            _state=State.DATA;
                        }
                        continue;
                }
            }

            if (_state==State.DATA && available>=_count)
            {
                _handler.onFrame(_more,_flags, _opcode, _buffer.get(_count));
                _count=0;
                _state=State.START;

                if (_buffer.length()==0)
                {
                    _buffers.returnBuffer(_buffer);
                    _buffer=null;
                }

                return total_filled;

            }
        }
    }

    /* ------------------------------------------------------------ */
    public void fill(Buffer buffer)
    {
        if (buffer!=null && buffer.length()>0)
        {
            if (_buffer==null)
                _buffer=_buffers.getBuffer();
            _buffer.put(buffer);
            buffer.clear();
        }
    }

}
