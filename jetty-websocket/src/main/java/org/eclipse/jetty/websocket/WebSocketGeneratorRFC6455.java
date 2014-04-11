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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;


/**
 * WebSocketGenerator.
 * This class generates websocket packets.
 * It is fully synchronized because it is likely that async
 * threads will call the addMessage methods while other
 * threads are flushing the generator.
 */
public class WebSocketGeneratorRFC6455 implements WebSocketGenerator
{
    private final Lock _lock = new ReentrantLock();
    private final WebSocketBuffers _buffers;
    private final EndPoint _endp;
    private final byte[] _mask = new byte[4];
    private final MaskGen _maskGen;
    private Buffer _buffer;
    private int _m;
    private boolean _opsent;
    private boolean _closed;

    public WebSocketGeneratorRFC6455(WebSocketBuffers buffers, EndPoint endp)
    {
        this(buffers, endp, null);
    }

    public WebSocketGeneratorRFC6455(WebSocketBuffers buffers, EndPoint endp, MaskGen maskGen)
    {
        _buffers = buffers;
        _endp = endp;
        _maskGen = maskGen;
    }

    public Buffer getBuffer()
    {
        _lock.lock();
        try
        {
            return _buffer;
        }
        finally
        {
            _lock.unlock();
        }
    }

    public void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        _lock.lock();
        try
        {
            if (_closed)
                throw new EofException("Closed");
            if (opcode == WebSocketConnectionRFC6455.OP_CLOSE)
                _closed = true;

            boolean mask = _maskGen != null;

            if (_buffer == null)
                _buffer = mask ? _buffers.getBuffer() : _buffers.getDirectBuffer();

            boolean last = WebSocketConnectionRFC6455.isLastFrame(flags);

            int space = mask ? 14 : 10;

            do
            {
                opcode = _opsent ? WebSocketConnectionRFC6455.OP_CONTINUATION : opcode;
                opcode = (byte)(((0xf & flags) << 4) + (0xf & opcode));
                _opsent = true;

                int payload = length;
                if (payload + space > _buffer.capacity())
                {
                    // We must fragement, so clear FIN bit
                    opcode = (byte)(opcode & 0x7F); // Clear the FIN bit
                    payload = _buffer.capacity() - space;
                }
                else if (last)
                    opcode = (byte)(opcode | 0x80); // Set the FIN bit

                // ensure there is space for header
                if (_buffer.space() <= space)
                {
                    flushBuffer();
                    if (_buffer.space() <= space)
                        flush();
                }

                // write the opcode and length
                if (payload > 0xffff)
                {
                    _buffer.put(new byte[]{
                            opcode,
                            mask ? (byte)0xff : (byte)0x7f,
                            (byte)0,
                            (byte)0,
                            (byte)0,
                            (byte)0,
                            (byte)((payload >> 24) & 0xff),
                            (byte)((payload >> 16) & 0xff),
                            (byte)((payload >> 8) & 0xff),
                            (byte)(payload & 0xff)});
                }
                else if (payload >= 0x7e)
                {
                    _buffer.put(new byte[]{
                            opcode,
                            mask ? (byte)0xfe : (byte)0x7e,
                            (byte)(payload >> 8),
                            (byte)(payload & 0xff)});
                }
                else
                {
                    _buffer.put(new byte[]{
                            opcode,
                            (byte)(mask ? (0x80 | payload) : payload)});
                }

                // write mask
                if (mask)
                {
                    _maskGen.genMask(_mask);
                    _m = 0;
                    _buffer.put(_mask);
                }

                // write payload
                int remaining = payload;
                while (remaining > 0)
                {
                    _buffer.compact();
                    int chunk = remaining < _buffer.space() ? remaining : _buffer.space();

                    if (mask)
                    {
                        for (int i = 0; i < chunk; i++)
                            _buffer.put((byte)(content[offset + (payload - remaining) + i] ^ _mask[+_m++ % 4]));
                    }
                    else
                        _buffer.put(content, offset + (payload - remaining), chunk);

                    remaining -= chunk;
                    if (_buffer.space() > 0)
                    {
                        // Gently flush the data, issuing a non-blocking write
                        flushBuffer();
                    }
                    else
                    {
                        // Forcibly flush the data, issuing a blocking write
                        flush();
                        if (remaining == 0)
                        {
                            // Gently flush the data, issuing a non-blocking write
                            flushBuffer();
                        }
                    }
                }
                offset += payload;
                length -= payload;
            }
            while (length > 0);
            _opsent = !last;

            if (_buffer != null && _buffer.length() == 0)
            {
                _buffers.returnBuffer(_buffer);
                _buffer = null;
            }
        }
        finally
        {
            _lock.unlock();
        }
    }

    public int flushBuffer() throws IOException
    {
        if (!_lock.tryLock())
            return 0;

        try
        {
            if (!_endp.isOpen())
                throw new EofException();

            if (_buffer != null)
            {
                int flushed = _buffer.hasContent() ? _endp.flush(_buffer) : 0;
                if (_closed && _buffer.length() == 0)
                    _endp.shutdownOutput();
                return flushed;
            }

            return 0;
        }
        finally
        {
            _lock.unlock();
        }
    }

    public int flush() throws IOException
    {
        if (!_lock.tryLock())
            return 0;

        try
        {
            if (_buffer == null)
                return 0;

            int result = flushBuffer();
            if (!_endp.isBlocking())
            {
                long now = System.currentTimeMillis();
                long end = now + _endp.getMaxIdleTime();
                while (_buffer.length() > 0)
                {
                    boolean ready = _endp.blockWritable(end - now);
                    if (!ready)
                    {
                        now = System.currentTimeMillis();
                        if (now < end)
                            continue;
                        throw new IOException("Write timeout");
                    }

                    result += flushBuffer();
                }
            }
            _buffer.compact();
            return result;
        }
        finally
        {
            _lock.unlock();
        }
    }

    public boolean isBufferEmpty()
    {
        _lock.lock();
        try
        {
            return _buffer == null || _buffer.length() == 0;
        }
        finally
        {
            _lock.unlock();
        }
    }

    public void returnBuffer()
    {
        _lock.lock();
        try
        {
            if (_buffer != null && _buffer.length() == 0)
            {
                _buffers.returnBuffer(_buffer);
                _buffer = null;
            }
        }
        finally
        {
            _lock.unlock();
        }
    }

    @Override
    public String toString()
    {
        // Do NOT use synchronized (this)
        // because it's very easy to deadlock when debugging is enabled.
        // We do a best effort to print the right toString() and that's it.
        Buffer buffer = _buffer;
        return String.format("%s@%x closed=%b buffer=%d",
                getClass().getSimpleName(),
                hashCode(),
                _closed,
                buffer == null ? -1 : buffer.length());
    }
}
