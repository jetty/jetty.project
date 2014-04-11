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
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;


/* ------------------------------------------------------------ */
/** WebSocketGenerator.
 * This class generates websocket packets.
 * It is fully synchronized because it is likely that async
 * threads will call the addMessage methods while other
 * threads are flushing the generator.
 */
public class WebSocketGeneratorD06 implements WebSocketGenerator
{
    final private WebSocketBuffers _buffers;
    final private EndPoint _endp;
    private Buffer _buffer;
    private final byte[] _mask=new byte[4];
    private int _m;
    private boolean _opsent;
    private final MaskGen _maskGen;

    public WebSocketGeneratorD06(WebSocketBuffers buffers, EndPoint endp)
    {
        _buffers=buffers;
        _endp=endp;
        _maskGen=null;
    }

    public WebSocketGeneratorD06(WebSocketBuffers buffers, EndPoint endp, MaskGen maskGen)
    {
        _buffers=buffers;
        _endp=endp;
        _maskGen=maskGen;
    }

    public synchronized void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        // System.err.printf("<< %s %s %s\n",TypeUtil.toHexString(flags),TypeUtil.toHexString(opcode),length);

        long blockFor=_endp.getMaxIdleTime();

        if (_buffer==null)
            _buffer=(_maskGen!=null)?_buffers.getBuffer():_buffers.getDirectBuffer();

        boolean last=WebSocketConnectionD06.isLastFrame(flags);
        opcode=(byte)(((0xf&flags)<<4)+0xf&opcode);

        int space=(_maskGen!=null)?14:10;

        do
        {
            opcode = _opsent?WebSocketConnectionD06.OP_CONTINUATION:opcode;
            _opsent=true;

            int payload=length;
            if (payload+space>_buffer.capacity())
            {
                // We must fragement, so clear FIN bit
                opcode&=(byte)0x7F; // Clear the FIN bit
                payload=_buffer.capacity()-space;
            }
            else if (last)
                opcode|=(byte)0x80; // Set the FIN bit

            // ensure there is space for header
            if (_buffer.space() <= space)
                expelBuffer(blockFor);

            // write mask
            if ((_maskGen!=null))
            {
                _maskGen.genMask(_mask);
                _m=0;
                _buffer.put(_mask);
            }

            // write the opcode and length
            if (payload>0xffff)
            {
                bufferPut(new byte[]{
                        opcode,
                        (byte)0x7f,
                        (byte)0,
                        (byte)0,
                        (byte)0,
                        (byte)0,
                        (byte)((payload>>24)&0xff),
                        (byte)((payload>>16)&0xff),
                        (byte)((payload>>8)&0xff),
                        (byte)(payload&0xff)});
            }
            else if (payload >=0x7e)
            {
                bufferPut(new byte[]{
                        opcode,
                        (byte)0x7e,
                        (byte)(payload>>8),
                        (byte)(payload&0xff)});
            }
            else
            {
                bufferPut(opcode);
                bufferPut((byte)payload);
            }

            // write payload
            int remaining = payload;
            while (remaining > 0)
            {
                _buffer.compact();
                int chunk = remaining < _buffer.space() ? remaining : _buffer.space();

                if ((_maskGen!=null))
                {
                    for (int i=0;i<chunk;i++)
                        bufferPut(content[offset+ (payload-remaining)+i]);
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
                    expelBuffer(blockFor);
                    if (remaining == 0)
                    {
                        // Gently flush the data, issuing a non-blocking write
                        flushBuffer();
                    }
                }
            }
            offset+=payload;
            length-=payload;
        }
        while (length>0);
        _opsent=!last;
    }

    private synchronized void bufferPut(byte[] data) throws IOException
    {
        if (_maskGen!=null)
            for (int i=0;i<data.length;i++)
                data[i]^=_mask[+_m++%4];
        _buffer.put(data);
    }

    private synchronized void bufferPut(byte data) throws IOException
    {
        _buffer.put((byte)(data^_mask[+_m++%4]));
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
