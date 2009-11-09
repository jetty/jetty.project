package org.eclipse.jetty.websocket;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;


/* ------------------------------------------------------------ */
/** WebSocketGenerator.
 * This class generates websocket packets.
 * It is fully synchronized because it is likely that async 
 * threads will call the addMessage methods while other 
 * threads are flushing the generator.
 */
public class WebSocketGenerator
{
    final private Buffers _buffers;
    final private EndPoint _endp;
    private Buffer _buffer;
    
    public WebSocketGenerator(Buffers buffers, EndPoint endp)
    {
        _buffers=buffers;
        _endp=endp;
    }

    synchronized public boolean addMessage(byte frame,Buffer content, long blockFor) throws IOException
    {
        if (_buffer==null)
            _buffer=_buffers.getBuffer();
        else if (_buffer.length()>0)
            flushBuffer();

        int length=content.length();
        if (length>2097152)
            throw new IllegalArgumentException("too big");
        
        int length_bytes=(length>16384)?3:(length>128)?2:1;
        
        if (_buffer.space()<length+1+length_bytes)
        {
            // TODO block if there can be space
            throw new IllegalArgumentException("no space");
        }
        
        _buffer.put((byte)(0x80|frame));

        switch (length_bytes)
        {
            case 3:
                _buffer.put((byte)(0x80|(length>>14)));
            case 2:
                _buffer.put((byte)(0x80|(0x7f&(length>>7))));
            case 1:
                _buffer.put((byte)(0x7f&length));
        }

        _buffer.put(content);
        return true;
    }

    synchronized public boolean addMessage(byte frame, String content, long blockFor) throws IOException
    {
        if (_buffer==null)
            _buffer=_buffers.getBuffer();
        else if (_buffer.length()>0)
            flushBuffer();

        int length=content.length();
        int space=waitForSpace(length+2,blockFor);
        
        _buffer.put((byte)(0x7f&frame));
        
        for (int i = 0; i < length; i++)
        {
            int code = content.charAt(i);

            if ((code & 0xffffff80) == 0) 
            {
                // 1b
                if (space<1)
                    space=waitForSpace(1,blockFor);
                _buffer.put((byte)(code));
                space--;
            }
            else if((code&0xfffff800)==0)
            {
                // 2b
                if (space<2)
                    space=waitForSpace(2,blockFor);
                _buffer.put((byte)(0xc0|(code>>6)));
                _buffer.put((byte)(0x80|(code&0x3f)));
                space-=2;
            }
            else if((code&0xffff0000)==0)
            {
                // 3b
                if (space<3)
                    space=waitForSpace(3,blockFor);
                _buffer.put((byte)(0xe0|(code>>12)));
                _buffer.put((byte)(0x80|((code>>6)&0x3f)));
                _buffer.put((byte)(0x80|(code&0x3f)));
                space-=3;
            }
            else if((code&0xff200000)==0)
            {
                // 4b
                if (space<4)
                    space=waitForSpace(4,blockFor);
                _buffer.put((byte)(0xf0|(code>>18)));
                _buffer.put((byte)(0x80|((code>>12)&0x3f)));
                _buffer.put((byte)(0x80|((code>>6)&0x3f)));
                _buffer.put((byte)(0x80|(code&0x3f)));
                space-=4;
            }
            else if((code&0xf4000000)==0)
            {
                // 5b
                if (space<5)
                    space=waitForSpace(5,blockFor);
                _buffer.put((byte)(0xf8|(code>>24)));
                _buffer.put((byte)(0x80|((code>>18)&0x3f)));
                _buffer.put((byte)(0x80|((code>>12)&0x3f)));
                _buffer.put((byte)(0x80|((code>>6)&0x3f)));
                _buffer.put((byte)(0x80|(code&0x3f)));
                space-=5;
            }
            else if((code&0x80000000)==0)
            {
                // 6b
                if (space<6)
                    space=waitForSpace(6,blockFor);
                _buffer.put((byte)(0xfc|(code>>30)));
                _buffer.put((byte)(0x80|((code>>24)&0x3f)));
                _buffer.put((byte)(0x80|((code>>18)&0x3f)));
                _buffer.put((byte)(0x80|((code>>12)&0x3f)));
                _buffer.put((byte)(0x80|((code>>6)&0x3f)));
                _buffer.put((byte)(0x80|(code&0x3f)));
                space-=6;
            }
            else
            {
                _buffer.put((byte)('?'));
                space-=1;
            }
        }

        if (space<1)
            space=waitForSpace(1,blockFor);
        _buffer.put((byte)(0xff));
        
        return true;
    }
    
    private int waitForSpace(int needed, long blockFor)
    {
        int space=_buffer.space();
        
        if (space<needed)
        {
            _buffer.compact();
            space=_buffer.space();
            if (space<needed)
                // TODO flush and wait for space 
                throw new IllegalStateException("no space");
        }
        return space;
    }

    synchronized public int flush(long blockFor)
    {
        return 0;
    }

    synchronized public int flush() throws IOException
    {
        int flushed = flushBuffer();
        if (_buffer.length()==0)
        {
            _buffers.returnBuffer(_buffer);
            _buffer=null;
        }
        return flushed;
    }
    
    private int flushBuffer() throws IOException
    {
        if (!_endp.isOpen())
            return -1;
        
        if (_buffer!=null)
        {
            int flushed =_endp.flush(_buffer);
            if (flushed>0)
                _buffer.skip(flushed);
            return flushed;
        }
        return 0;
    }

    synchronized public boolean isBufferEmpty()
    {
        return _buffer==null || _buffer.length()==0;
    }

}
