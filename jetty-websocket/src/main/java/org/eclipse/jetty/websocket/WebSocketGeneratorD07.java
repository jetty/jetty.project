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
import java.security.SecureRandom;
import java.util.Random;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.TypeUtil;


/* ------------------------------------------------------------ */
/** WebSocketGenerator.
 * This class generates websocket packets.
 * It is fully synchronized because it is likely that async
 * threads will call the addMessage methods while other
 * threads are flushing the generator.
 */
public class WebSocketGeneratorD07 implements WebSocketGenerator
{
    final private WebSocketBuffers _buffers;
    final private EndPoint _endp;
    private Buffer _buffer;
    private final byte[] _mask=new byte[4];
    private int _m;
    private boolean _opsent;
    private final MaskGen _maskGen;

    public interface MaskGen
    {
        void genMask(byte[] mask);
    }
    
    public static class NullMaskGen implements MaskGen
    {
        public void genMask(byte[] mask)
        {
            mask[0]=mask[1]=mask[2]=mask[3]=0;
        }
    }
    
    public static class FixedMaskGen implements MaskGen
    {
        final byte[] _mask;
        public FixedMaskGen()
        {
            _mask=new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff};
        }
        
        public FixedMaskGen(byte[] mask)
        {
            _mask=mask;
        }
        
        public void genMask(byte[] mask)
        {
            mask[0]=_mask[0];
            mask[1]=_mask[1];
            mask[2]=_mask[2];
            mask[3]=_mask[3];
        }
    }

    public static class RandomMaskGen implements MaskGen
    {
        final Random _random;
        public RandomMaskGen()
        {
            _random=new SecureRandom(); 
        }
        
        public RandomMaskGen(Random random)
        {
            _random=random;
        }
        
        public void genMask(byte[] mask)
        {
            _random.nextBytes(mask);
        }
    }

    
    public WebSocketGeneratorD07(WebSocketBuffers buffers, EndPoint endp)
    {
        _buffers=buffers;
        _endp=endp;
        _maskGen=null;
    }
    
    public WebSocketGeneratorD07(WebSocketBuffers buffers, EndPoint endp, MaskGen maskGen)
    {
        _buffers=buffers;
        _endp=endp;
        _maskGen=maskGen;
    }

    public synchronized void addFrame(byte flags, byte opcode, byte[] content, int offset, int length) throws IOException
    {
        // System.err.printf("<< %s %s %s\n",TypeUtil.toHexString(flags),TypeUtil.toHexString(opcode),length);
        
        boolean mask=_maskGen!=null;
        
        if (_buffer==null)
            _buffer=mask?_buffers.getBuffer():_buffers.getDirectBuffer();
            
        boolean last=WebSocketConnectionD07.isLastFrame(flags);
        byte orig=opcode;
        
        int space=mask?14:10;
        
        do
        {
            opcode = _opsent?WebSocketConnectionD07.OP_CONTINUATION:opcode;
            opcode=(byte)(((0xf&flags)<<4)+(0xf&opcode));
            _opsent=true;
            
            int payload=length;
            if (payload+space>_buffer.capacity())
            {
                // We must fragement, so clear FIN bit
                opcode=(byte)(opcode&0x7F); // Clear the FIN bit
                payload=_buffer.capacity()-space;
            }
            else if (last)
                opcode= (byte)(opcode|0x80); // Set the FIN bit
      
            // ensure there is space for header
            if (_buffer.space() <= space)
            {
                flushBuffer();
                if (_buffer.space() <= space)
                    flush();
            }
            
            // write the opcode and length
            if (payload>0xffff)
            {
                _buffer.put(new byte[]{
                        opcode,
                        mask?(byte)0xff:(byte)0x7f,
                        (byte)((payload>>56)&0x7f),
                        (byte)((payload>>48)&0xff), 
                        (byte)((payload>>40)&0xff), 
                        (byte)((payload>>32)&0xff),
                        (byte)((payload>>24)&0xff),
                        (byte)((payload>>16)&0xff),
                        (byte)((payload>>8)&0xff),
                        (byte)(payload&0xff)});
            }
            else if (payload >=0x7e)
            {
                _buffer.put(new byte[]{
                        opcode,
                        mask?(byte)0xfe:(byte)0x7e,
                        (byte)(payload>>8),
                        (byte)(payload&0xff)});
            }
            else
            {
                _buffer.put(new byte[]{ 
                        opcode,
                        (byte)(mask?(0x80|payload):payload)});
            }

            // write mask
            if (mask)
            {
                _maskGen.genMask(_mask);
                _m=0;
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
                    for (int i=0;i<chunk;i++)
                        _buffer.put((byte)(content[offset+ (payload-remaining)+i]^_mask[+_m++%4]));
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
            offset+=payload;
            length-=payload;
        }
        while (length>0);
        _opsent=!last;
        
        if (_buffer!=null && _buffer.length()==0)
        {
            _buffers.returnBuffer(_buffer);
            _buffer=null;
        }
    }

    public synchronized int flushBuffer() throws IOException
    {
        if (!_endp.isOpen())
            throw new EofException();

        if (_buffer!=null)
            return _endp.flush(_buffer);

        return 0;
    }

    public synchronized int flush() throws IOException
    {
        if (_buffer==null)
            return 0;
        int result = flushBuffer();
        
        if (!_endp.isBlocking())
        {
            long now = System.currentTimeMillis();
            long end=now+_endp.getMaxIdleTime();
            while (_buffer.length()>0)
            {
                boolean ready = _endp.blockWritable(end-now);
                if (!ready)
                {
                    now = System.currentTimeMillis();
                    if (now<end)
                        continue;
                    throw new IOException("Write timeout");
                }

                result += flushBuffer();
            }
        }
        _buffer.compact();
        return result;
    }

    public synchronized boolean isBufferEmpty()
    {
        return _buffer==null || _buffer.length()==0;
    }

    public synchronized void idle()
    {
        if (_buffer!=null && _buffer.length()==0)
        {
            _buffers.returnBuffer(_buffer);
            _buffer=null;
        }
    }

}
