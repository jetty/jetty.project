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
import java.math.BigInteger;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;


/* ------------------------------------------------------------ */
/** WebSocketGenerator.
 * This class generates websocket packets.
 * It is fully synchronized because it is likely that async
 * threads will call the addMessage methods while other
 * threads are flushing the generator.
 */
public class WebSocketGeneratorD00 implements WebSocketGenerator
{
    final private WebSocketBuffers _buffers;
    final private EndPoint _endp;
    private Buffer _buffer;

    public WebSocketGeneratorD00(WebSocketBuffers buffers, EndPoint endp)
    {
        _buffers=buffers;
        _endp=endp;
    }
    
    public synchronized void addFrame(byte flags, byte opcode,byte[] content, int offset, int length) throws IOException
    {
        long blockFor=_endp.getMaxIdleTime();
        
        if (_buffer==null)
            _buffer=_buffers.getDirectBuffer();

        if (_buffer.space() == 0)
            expelBuffer(blockFor);

        bufferPut(opcode, blockFor);

        if (isLengthFrame(opcode))
        {
            // Send a length delimited frame

            // How many bytes we need for the length ?
            // We have 7 bits available, so log2(length) / 7 + 1
            // For example, 50000 bytes is 2 8-bytes: 11000011 01010000
            // but we need to write it in 3 7-bytes 0000011 0000110 1010000
            // 65536 == 1 00000000 00000000 => 100 0000000 0000000
            int lengthBytes = new BigInteger(String.valueOf(length)).bitLength() / 7 + 1;
            for (int i = lengthBytes - 1; i > 0; --i)
            {
                byte lengthByte = (byte)(0x80 | (0x7F & (length >> 7 * i)));
                bufferPut(lengthByte, blockFor);
            }
            bufferPut((byte)(0x7F & length), blockFor);
        }

        int remaining = length;
        while (remaining > 0)
        {
            int chunk = remaining < _buffer.space() ? remaining : _buffer.space();
            _buffer.put(content, offset + (length - remaining), chunk);
            remaining -= chunk;
            if (_buffer.space() > 0)
            {
                if (!isLengthFrame(opcode))
                    _buffer.put((byte)0xFF);
                // Gently flush the data, issuing a non-blocking write
                flushBuffer();
            }
            else
            {
                // Forcibly flush the data, issuing a blocking write
                expelBuffer(blockFor);
                if (remaining == 0)
                {
                    if (!isLengthFrame(opcode))
                        _buffer.put((byte)0xFF);
                    // Gently flush the data, issuing a non-blocking write
                    flushBuffer();
                }
            }
        }
    }

    private synchronized boolean isLengthFrame(byte frame)
    {
        return (frame & WebSocketConnectionD00.LENGTH_FRAME) == WebSocketConnectionD00.LENGTH_FRAME;
    }

    private synchronized void bufferPut(byte datum, long blockFor) throws IOException
    {
        if (_buffer==null)
            _buffer=_buffers.getDirectBuffer();
        _buffer.put(datum);
        if (_buffer.space() == 0)
            expelBuffer(blockFor);
    }

    public synchronized int flush(int blockFor) throws IOException
    {
        return expelBuffer(blockFor);
    }

    public synchronized int flush() throws IOException
    {
        int flushed = flushBuffer();
        if (_buffer!=null && _buffer.length()==0)
        {
            _buffers.returnBuffer(_buffer);
            _buffer=null;
        }
        return flushed;
    }

    private synchronized int flushBuffer() throws IOException
    {
        if (!_endp.isOpen())
            throw new EofException();

        if (_buffer!=null && _buffer.hasContent())
            return _endp.flush(_buffer);

        return 0;
    }

    private synchronized int expelBuffer(long blockFor) throws IOException
    {
        if (_buffer==null)
            return 0;
        int result = flushBuffer();
        _buffer.compact();
        if (!_endp.isBlocking())
        {
            while (_buffer.space()==0)
            {
                boolean ready = _endp.blockWritable(blockFor);
                if (!ready)
                    throw new IOException("Write timeout");

                result += flushBuffer();
                _buffer.compact();
            }
        }
        return result;
    }

    public synchronized boolean isBufferEmpty()
    {
        return _buffer==null || _buffer.length()==0;
    }
    
}
