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

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;




/* ------------------------------------------------------------ */
/**
 * Parser the WebSocket protocol.
 *
 */
public class WebSocketParserD00 implements WebSocketParser
{
    private static final Logger LOG = Log.getLogger(WebSocketParserD00.class);

    public static final int STATE_START=0;
    public static final int STATE_SENTINEL_DATA=1;
    public static final int STATE_LENGTH=2;
    public static final int STATE_DATA=3;

    private final WebSocketBuffers _buffers;
    private final EndPoint _endp;
    private final FrameHandler _handler;
    private int _state;
    private Buffer _buffer;
    private byte _opcode;
    private int _length;

    /* ------------------------------------------------------------ */
    /**
     * @param buffers The buffers to use for parsing.  Only the {@link Buffers#getBuffer()} is used.
     * This should be a direct buffer if binary data is mostly used or an indirect buffer if utf-8 data
     * is mostly used.
     * @param endp the endpoint
     * @param handler the handler to notify when a parse event occurs
     */
    public WebSocketParserD00(WebSocketBuffers buffers, EndPoint endp, FrameHandler handler)
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
     * Parse to the next {@link WebSocketParser.FrameHandler} event or until no more data is
     * available. Fill data from the {@link EndPoint} only as necessary.
     * @return An indication of progress or otherwise. -1 indicates EOF, 0 indicates
     * that no bytes were read and no messages parsed. A positive number indicates either
     * the bytes filled or the messages parsed.
     */
    public int parseNext()
    {
        if (_buffer==null)
            _buffer=_buffers.getBuffer();

        int progress=0;

        // Loop until an datagram call back or can't fill anymore
        while(true)
        {
            int length=_buffer.length();

            // Fill buffer if we need a byte or need length
            if (length == 0 || _state==STATE_DATA && length<_length)
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
                        return progress;
                    progress+=filled;
                    length=_buffer.length();
                }
                catch(IOException e)
                {
                    LOG.debug(e);
                    return progress>0?progress:-1;
                }
            }


            // Parse the buffer byte by byte (unless it is STATE_DATA)
            byte b;
            charloop: while (length-->0)
            {
                switch (_state)
                {
                    case STATE_START:
                        b=_buffer.get();
                        _opcode=b;
                        if (_opcode<0)
                        {
                            _length=0;
                            _state=STATE_LENGTH;
                        }
                        else
                        {
                            _state=STATE_SENTINEL_DATA;
                            _buffer.mark(0);
                        }
                        continue;

                    case STATE_SENTINEL_DATA:
                        b=_buffer.get();
                        if ((b&0xff)==0xff)
                        {
                            _state=STATE_START;
                            int l=_buffer.getIndex()-_buffer.markIndex()-1;
                            progress++;
                            _handler.onFrame((byte)0,_opcode,_buffer.sliceFromMark(l));
                            _buffer.setMarkIndex(-1);
                            if (_buffer.length()==0)
                            {
                                _buffers.returnBuffer(_buffer);
                                _buffer=null;
                            }
                            return progress;
                        }
                        continue;

                    case STATE_LENGTH:
                        b=_buffer.get();
                        _length=_length<<7 | (0x7f&b);
                        if (b>=0)
                        {
                            _state=STATE_DATA;
                            _buffer.mark(0);
                        }
                        continue;

                    case STATE_DATA:
                        if (_buffer.markIndex()<0)
                        if (_buffer.length()<_length)
                            break charloop;
                        Buffer data=_buffer.sliceFromMark(_length);
                        _buffer.skip(_length);
                        _state=STATE_START;
                        progress++;
                        _handler.onFrame((byte)0, _opcode, data);

                        if (_buffer.length()==0)
                        {
                            _buffers.returnBuffer(_buffer);
                            _buffer=null;
                        }

                        return progress;
                }
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
