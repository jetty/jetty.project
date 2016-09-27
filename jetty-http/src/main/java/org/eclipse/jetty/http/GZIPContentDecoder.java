//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

/**
 * Decoder for the "gzip" encoding.
 * 
 */
public class GZIPContentDecoder
{
    private final Inflater _inflater = new Inflater(true);
    private final ByteBufferPool _pool;
    private final int _bufferSize;
    private State _state;
    private int _size;
    private int _value;
    private byte _flags;
    private ByteBuffer _empty;
    private ByteBuffer _inflated;

    public GZIPContentDecoder()
    {
        this(null,2048);
    }

    public GZIPContentDecoder(int bufferSize)
    {
        this(null,bufferSize);
    }
    
    public GZIPContentDecoder(ByteBufferPool pool, int bufferSize)
    {
        _bufferSize = bufferSize;
        _pool = pool;
        reset();
    }

    public ByteBuffer decode(ByteBuffer compressed)
    {
        decodeChunks(compressed);
        if (BufferUtil.isEmpty(_inflated) || _state==State.CRC || _state==State.ISIZE )
            return BufferUtil.EMPTY_BUFFER;
        
        ByteBuffer result = _inflated;
        _inflated = null;
        return result;
    }

    protected boolean decodedChunk(ByteBuffer chunk)
    {
        if (_inflated==null)
            _inflated=chunk;
        else
        {
            int size = _inflated.remaining() + chunk.remaining();
            if (size<=_inflated.capacity())
            {
                BufferUtil.append(_inflated,chunk);
                BufferUtil.put(chunk,_inflated);
                release(chunk);
            }
            else
            {
                ByteBuffer bigger=_pool==null?BufferUtil.allocate(size):_pool.acquire(size,false);
                int pos=BufferUtil.flipToFill(bigger);
                BufferUtil.put(_inflated,bigger);
                BufferUtil.put(chunk,bigger);
                BufferUtil.flipToFlush(bigger,pos);
                release(_inflated);
                release(chunk);
                _inflated = bigger;
            }
        }
        
        return false;
    }
    
    
    protected void decodeChunks(ByteBuffer compressed)
    {
        try
        {
            while (true)
            {
                switch (_state)
                {
                    case INITIAL:
                    {
                        _state = State.ID;
                        break;
                    }

                    case FLAGS:
                    {
                        if ((_flags & 0x04) == 0x04)
                        {
                            _state = State.EXTRA_LENGTH;
                            _size = 0;
                            _value = 0;
                        }
                        else if ((_flags & 0x08) == 0x08)
                            _state = State.NAME;
                        else if ((_flags & 0x10) == 0x10)
                            _state = State.COMMENT;
                        else if ((_flags & 0x2) == 0x2)
                        {
                            _state = State.HCRC;
                            _size = 0;
                            _value = 0;
                        }
                        else
                        {
                            _state = State.DATA;
                            continue;
                        }
                        break;
                    }
                    
                    case DATA:
                    {
                        while (true)
                        {
                            ByteBuffer chunk = _empty==null?acquire():_empty;
                            _empty = chunk;
                            
                            try
                            {
                                int length = _inflater.inflate(chunk.array(),chunk.arrayOffset(),chunk.capacity());
                                chunk.limit(length);
                            }
                            catch (DataFormatException x)
                            {
                                _empty=null;
                                throw new ZipException(x.getMessage());
                            }
                            
                            if (chunk.hasRemaining())
                            {
                                _empty=null;
                                if (decodedChunk(chunk))
                                    return;
                            }
                            else if (_inflater.needsInput())
                            {
                                if (!compressed.hasRemaining())
                                    return;
                                if (compressed.hasArray())
                                {
                                    _inflater.setInput(compressed.array(),compressed.arrayOffset()+compressed.position(),compressed.remaining());
                                    compressed.position(compressed.limit());
                                }
                                else
                                {
                                    // TODO use the pool
                                    byte[] input = new byte[compressed.remaining()];
                                    compressed.get(input);
                                    _inflater.setInput(input);
                                }  
                            }
                            else if (_inflater.finished())
                            {
                                int remaining = _inflater.getRemaining();
                                compressed.position(compressed.limit() - remaining);
                                _state = State.CRC;
                                _size = 0;
                                _value = 0;
                                break;
                            }
                        }
                        continue;
                    }
                    
                    default:
                        break;
                }
        
                if (!compressed.hasRemaining())
                    break;
                
                byte currByte = compressed.get();
                switch (_state)
                {
                    case ID:
                    {
                        _value += (currByte & 0xFF) << 8 * _size;
                        ++_size;
                        if (_size == 2)
                        {
                            if (_value != 0x8B1F)
                                throw new ZipException("Invalid gzip bytes");
                            _state = State.CM;
                        }
                        break;
                    }
                    case CM:
                    {
                        if ((currByte & 0xFF) != 0x08)
                            throw new ZipException("Invalid gzip compression method");
                        _state = State.FLG;
                        break;
                    }
                    case FLG:
                    {
                        _flags = currByte;
                        _state = State.MTIME;
                        _size = 0;
                        _value = 0;
                        break;
                    }
                    case MTIME:
                    {
                        // Skip the 4 MTIME bytes
                        ++_size;
                        if (_size == 4)
                            _state = State.XFL;
                        break;
                    }
                    case XFL:
                    {
                        // Skip XFL
                        _state = State.OS;
                        break;
                    }
                    case OS:
                    {
                        // Skip OS
                        _state = State.FLAGS;
                        break;
                    }
                    case EXTRA_LENGTH:
                    {
                        _value += (currByte & 0xFF) << 8 * _size;
                        ++_size;
                        if (_size == 2)
                            _state = State.EXTRA;
                        break;
                    }
                    case EXTRA:
                    {
                        // Skip EXTRA bytes
                        --_value;
                        if (_value == 0)
                        {
                            // Clear the EXTRA flag and loop on the flags
                            _flags &= ~0x04;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case NAME:
                    {
                        // Skip NAME bytes
                        if (currByte == 0)
                        {
                            // Clear the NAME flag and loop on the flags
                            _flags &= ~0x08;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case COMMENT:
                    {
                        // Skip COMMENT bytes
                        if (currByte == 0)
                        {
                            // Clear the COMMENT flag and loop on the flags
                            _flags &= ~0x10;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case HCRC:
                    {
                        // Skip HCRC
                        ++_size;
                        if (_size == 2)
                        {
                            // Clear the HCRC flag and loop on the flags
                            _flags &= ~0x02;
                            _state = State.FLAGS;
                        }
                        break;
                    }
                    case CRC:
                    {
                        _value += (currByte & 0xFF) << 8 * _size;
                        ++_size;
                        if (_size == 4)
                        {
                            // From RFC 1952, compliant decoders need not to verify the CRC
                            _state = State.ISIZE;
                            _size = 0;
                            _value = 0;
                        }
                        break;
                    }
                    case ISIZE:
                    {
                        _value += (currByte & 0xFF) << 8 * _size;
                        ++_size;
                        if (_size == 4)
                        {
                            if (_value != _inflater.getBytesWritten())
                                throw new ZipException("Invalid input size");

                            // TODO ByteBuffer result = output == null ? BufferUtil.EMPTY_BUFFER : ByteBuffer.wrap(output);
                            reset();
                            return ;
                        }
                        break;
                    }
                    default:
                        throw new ZipException();
                }
            }
        }
        catch (ZipException x)
        {
            throw new RuntimeException(x);
        }
    }


    private void reset()
    {
        _inflater.reset();
        _state = State.INITIAL;
        _size = 0;
        _value = 0;
        _flags = 0;
        if (_empty!=null)
            release(_empty);
        _empty = null;
    }

    public boolean isFinished()
    {
        return _state == State.INITIAL;
    }

    private enum State
    {
        INITIAL, ID, CM, FLG, MTIME, XFL, OS, FLAGS, EXTRA_LENGTH, EXTRA, NAME, COMMENT, HCRC, DATA, CRC, ISIZE
    }
    
    public ByteBuffer acquire()
    {
        return _pool==null?BufferUtil.allocate(_bufferSize):_pool.acquire(_bufferSize,false);
    }
    
    public void release(ByteBuffer buffer)
    {
        if (_pool!=null && buffer!=BufferUtil.EMPTY_BUFFER)
            _pool.release(buffer);
    }
}
