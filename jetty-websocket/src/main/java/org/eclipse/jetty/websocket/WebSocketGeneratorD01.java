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
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;


/* ------------------------------------------------------------ */
/** WebSocketGenerator.
 * This class generates websocket packets.
 * It is fully synchronized because it is likely that async
 * threads will call the addMessage methods while other
 * threads are flushing the generator.
 */
public class WebSocketGeneratorD01 implements WebSocketGenerator
{
    final private WebSocketBuffers _buffers;
    final private EndPoint _endp;
    private Buffer _buffer;

    public WebSocketGeneratorD01(WebSocketBuffers buffers, EndPoint endp)
    {
        _buffers=buffers;
        _endp=endp;
    }

    public synchronized void addFrame(byte opcode,byte[] content, int blockFor) throws IOException
    {
        addFrame(opcode,content,0,content.length,blockFor);
    }
    

    public synchronized void addFrame(byte opcode,byte[] content, int offset, int length, int blockFor) throws IOException
    {
        addFragment(false,opcode,content,offset,length,blockFor);
    }

    public synchronized void addFragment(boolean more, byte opcode, byte[] content, int offset, int length, int blockFor) throws IOException
    {
        if (_buffer==null)
            _buffer=_buffers.getDirectBuffer();

        if (_buffer.space() == 0)
            expelBuffer(blockFor);
        
        opcode = (byte)(opcode & 0x0f);
        
        while (length>0)
        {
            // slice a fragment off
            int fragment=length;
            if (fragment+10>_buffer.capacity())
            {
                fragment=_buffer.capacity()-10;
                bufferPut((byte)(0x80|opcode), blockFor);
            }
            else if (more)
                bufferPut((byte)(0x80|opcode), blockFor);
            else
                bufferPut(opcode, blockFor);

            if (fragment>0xffff)
            {
                bufferPut((byte)0x7f, blockFor);
                bufferPut((byte)((fragment>>56)&0x7f), blockFor);
                bufferPut((byte)((fragment>>48)&0xff), blockFor);
                bufferPut((byte)((fragment>>40)&0xff), blockFor);
                bufferPut((byte)((fragment>>32)&0xff), blockFor);
                bufferPut((byte)((fragment>>24)&0xff), blockFor);
                bufferPut((byte)((fragment>>16)&0xff), blockFor);
                bufferPut((byte)((fragment>>8)&0xff), blockFor);
                bufferPut((byte)(fragment&0xff), blockFor);
            }
            else if (fragment >=0x7e)
            {
                bufferPut((byte)126, blockFor);
                bufferPut((byte)(fragment>>8), blockFor);
                bufferPut((byte)(fragment&0xff), blockFor);
            }
            else
            {
                bufferPut((byte)fragment, blockFor);
            }

            int remaining = fragment;
            while (remaining > 0)
            {
                _buffer.compact();
                int chunk = remaining < _buffer.space() ? remaining : _buffer.space();
                _buffer.put(content, offset + (fragment - remaining), chunk);
                remaining -= chunk;
                if (_buffer.space() > 0)
                {
                    // Gently flush the data, issuing a non-blocking write
                    flushBuffer();
                }
                else
                {
                    // Forcibly flush the data, issuing a blocking write
                    expelBuffer(blockFor);
                    if (remaining == 0)
                    {
                        // Gently flush the data, issuing a non-blocking write
                        flushBuffer();
                    }
                }
            }
            offset+=fragment;
            length-=fragment;
        }
    }

    private synchronized void bufferPut(byte datum, long blockFor) throws IOException
    {
        if (_buffer==null)
            _buffer=_buffers.getDirectBuffer();
        _buffer.put(datum);
        if (_buffer.space() == 0)
            expelBuffer(blockFor);
    }

    public synchronized void addFrame(byte frame, String content, int blockFor) throws IOException
    {
        byte[] bytes = content.getBytes("UTF-8");
        addFrame(frame, bytes, 0, bytes.length, blockFor);
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

        if (_buffer!=null)
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
                // TODO: in case the I/O system signals write ready, but when we attempt to write we cannot
                // TODO: we should decrease the blockFor timeout instead of waiting again the whole timeout
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
