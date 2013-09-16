//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;



/* ------------------------------------------------------------ */
/**
 * Parser the WebSocket protocol.
 *
 */
public class WebSocketParserD08 implements WebSocketParser
{
    private static final Logger LOG = Log.getLogger(WebSocketParserD08.class);

    public enum State {

        START(0), OPCODE(1), LENGTH_7(1), LENGTH_16(2), LENGTH_63(8), MASK(4), PAYLOAD(0), DATA(0), SKIP(1);

        int _needs;

        State(int needs)
        {
            _needs=needs;
        }

        int getNeeds()
        {
            return _needs;
        }
    }

    private final WebSocketBuffers _buffers;
    private final EndPoint _endp;
    private final FrameHandler _handler;
    private final boolean _shouldBeMasked;
    private State _state;
    private Buffer _buffer;
    private byte _flags;
    private byte _opcode;
    private int _bytesNeeded;
    private long _length;
    private boolean _masked;
    private final byte[] _mask = new byte[4];
    private int _m;
    private boolean _skip;
    private boolean _fakeFragments=true;

    /* ------------------------------------------------------------ */
    /**
     * @param buffers The buffers to use for parsing.  Only the {@link Buffers#getBuffer()} is used.
     * This should be a direct buffer if binary data is mostly used or an indirect buffer if utf-8 data
     * is mostly used.
     * @param endp the endpoint
     * @param handler the handler to notify when a parse event occurs
     * @param shouldBeMasked whether masking should be handled
     */
    public WebSocketParserD08(WebSocketBuffers buffers, EndPoint endp, FrameHandler handler, boolean shouldBeMasked)
    {
        _buffers=buffers;
        _endp=endp;
        _handler=handler;
        _shouldBeMasked=shouldBeMasked;
        _state=State.START;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if fake fragments should be created for frames larger than the buffer.
     */
    public boolean isFakeFragments()
    {
        return _fakeFragments;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param fakeFragments True if fake fragments should be created for frames larger than the buffer.
     */
    public void setFakeFragments(boolean fakeFragments)
    {
        _fakeFragments = fakeFragments;
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
        int total_filled=0;
        int events=0;

        // Loop until a datagram call back or can't fill anymore
        while(true)
        {
            int available=_buffer.length();

            // Fill buffer if we need a byte or need length
            while (available<(_state==State.SKIP?1:_bytesNeeded))
            {
                // compact to mark (set at start of data)
                _buffer.compact();

                // if no space, then the data is too big for buffer
                if (_buffer.space() == 0)
                {
                    // Can we send a fake frame?
                    if (_fakeFragments && _state==State.DATA)
                    {
                        Buffer data =_buffer.get(4*(available/4));
                        _buffer.compact();
                        if (_masked)
                        {
                            if (data.array()==null)
                                data=_buffer.asMutableBuffer();
                            byte[] array = data.array();
                            final int end=data.putIndex();
                            for (int i=data.getIndex();i<end;i++)
                                array[i]^=_mask[_m++%4];
                        }

                        // System.err.printf("%s %s %s >>\n",TypeUtil.toHexString(_flags),TypeUtil.toHexString(_opcode),data.length());
                        events++;
                        _bytesNeeded-=data.length();
                        _handler.onFrame((byte)(_flags&(0xff^WebSocketConnectionD08.FLAG_FIN)), _opcode, data);

                        _opcode=WebSocketConnectionD08.OP_CONTINUATION;
                    }

                    if (_buffer.space() == 0)
                    throw new IllegalStateException("FULL: "+_state+" "+_bytesNeeded+">"+_buffer.capacity());
                }

                // catch IOExceptions (probably EOF) and try to parse what we have
                try
                {
                    int filled=_endp.isOpen()?_endp.fill(_buffer):-1;
                    if (filled<=0)
                        return (total_filled+events)>0?(total_filled+events):filled;
                    total_filled+=filled;
                    available=_buffer.length();
                }
                catch(IOException e)
                {
                    LOG.debug(e);
                    return (total_filled+events)>0?(total_filled+events):-1;
                }
            }

            // if we are here, then we have sufficient bytes to process the current state.

            // Parse the buffer byte by byte (unless it is STATE_DATA)
            byte b;
            while (_state!=State.DATA && available>=(_state==State.SKIP?1:_bytesNeeded))
            {
                switch (_state)
                {
                    case START:
                        _skip=false;
                        _state=State.OPCODE;
                        _bytesNeeded=_state.getNeeds();
                        continue;

                    case OPCODE:
                        b=_buffer.get();
                        available--;
                        _opcode=(byte)(b&0xf);
                        _flags=(byte)(0xf&(b>>4));

                        if (WebSocketConnectionD08.isControlFrame(_opcode)&&!WebSocketConnectionD08.isLastFrame(_flags))
                        {
                            events++;
                            LOG.warn("Fragmented Control from "+_endp);
                            _handler.close(WebSocketConnectionD08.CLOSE_PROTOCOL,"Fragmented control");
                            _skip=true;
                        }

                        _state=State.LENGTH_7;
                        _bytesNeeded=_state.getNeeds();

                        continue;

                    case LENGTH_7:
                        b=_buffer.get();
                        available--;
                        _masked=(b&0x80)!=0;
                        b=(byte)(0x7f&b);

                        switch(b)
                        {
                            case 0x7f:
                                _length=0;
                                _state=State.LENGTH_63;
                                break;
                            case 0x7e:
                                _length=0;
                                _state=State.LENGTH_16;
                                break;
                            default:
                                _length=(0x7f&b);
                                _state=_masked?State.MASK:State.PAYLOAD;
                        }
                        _bytesNeeded=_state.getNeeds();
                        continue;

                    case LENGTH_16:
                        b=_buffer.get();
                        available--;
                        _length = _length*0x100 + (0xff&b);
                        if (--_bytesNeeded==0)
                        {
                            if (_length>_buffer.capacity() && !_fakeFragments)
                            {
                                events++;
                                _handler.close(WebSocketConnectionD08.CLOSE_BADDATA,"frame size "+_length+">"+_buffer.capacity());
                                _skip=true;
                            }

                            _state=_masked?State.MASK:State.PAYLOAD;
                            _bytesNeeded=_state.getNeeds();
                        }
                        continue;

                    case LENGTH_63:
                        b=_buffer.get();
                        available--;
                        _length = _length*0x100 + (0xff&b);
                        if (--_bytesNeeded==0)
                        {
                            _bytesNeeded=(int)_length;
                            if (_length>=_buffer.capacity() && !_fakeFragments)
                            {
                                events++;
                                _handler.close(WebSocketConnectionD08.CLOSE_BADDATA,"frame size "+_length+">"+_buffer.capacity());
                                _skip=true;
                            }

                            _state=_masked?State.MASK:State.PAYLOAD;
                            _bytesNeeded=_state.getNeeds();
                        }
                        continue;

                    case MASK:
                        _buffer.get(_mask,0,4);
                        _m=0;
                        available-=4;
                        _state=State.PAYLOAD;
                        _bytesNeeded=_state.getNeeds();
                        break;

                    case PAYLOAD:
                        _bytesNeeded=(int)_length;
                        _state=_skip?State.SKIP:State.DATA;
                        break;

                    case DATA:
                        break;

                    case SKIP:
                        int skip=Math.min(available,_bytesNeeded);
                        _buffer.skip(skip);
                        available-=skip;
                        _bytesNeeded-=skip;
                        if (_bytesNeeded==0)
                            _state=State.START;

                }
            }

            if (_state==State.DATA && available>=_bytesNeeded)
            {
                if ( _masked!=_shouldBeMasked)
                {
                    _buffer.skip(_bytesNeeded);
                    _state=State.START;
                    events++;
                    _handler.close(WebSocketConnectionD08.CLOSE_PROTOCOL,"bad mask");
                }
                else
                {
                    Buffer data =_buffer.get(_bytesNeeded);
                    if (_masked)
                    {
                        if (data.array()==null)
                            data=_buffer.asMutableBuffer();
                        byte[] array = data.array();
                        final int end=data.putIndex();
                        for (int i=data.getIndex();i<end;i++)
                            array[i]^=_mask[_m++%4];
                    }

                    // System.err.printf("%s %s %s >>\n",TypeUtil.toHexString(_flags),TypeUtil.toHexString(_opcode),data.length());
                    events++;
                    _handler.onFrame(_flags, _opcode, data);
                    _bytesNeeded=0;
                    _state=State.START;
                }

                return total_filled+events;
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

    /* ------------------------------------------------------------ */
    public void returnBuffer()
    {
        if (_buffer!=null && _buffer.length()==0)
        {
            _buffers.returnBuffer(_buffer);
            _buffer=null;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        Buffer buffer=_buffer;
        return WebSocketParserD08.class.getSimpleName()+"@"+ Integer.toHexString(hashCode())+"|"+_state+"|"+(buffer==null?"<>":buffer.toDetailString());
    }

}
