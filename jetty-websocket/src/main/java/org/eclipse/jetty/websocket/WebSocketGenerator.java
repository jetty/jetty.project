package org.eclipse.jetty.websocket;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
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
    final private WebSocketBuffers _buffers;
    final private EndPoint _endp;
    private Buffer _buffer;
    
    public WebSocketGenerator(WebSocketBuffers buffers, EndPoint endp)
    {
        _buffers=buffers;
        _endp=endp;
    }

    synchronized public void addFrame(byte frame,byte[] content, int blockFor) throws IOException
    {
        addFrame(frame,content,0,content.length,blockFor);
    }
    
    synchronized public void addFrame(byte frame,byte[] content, int offset, int length, int blockFor) throws IOException
    {
        if (_buffer==null)
            _buffer=_buffers.getDirectBuffer();
        
        if ((frame&0x80)==0x80)
        {
            // Send in a length delimited frame
            
            // maximum of 3 byte length == 21 bits
            if (length>2097152)
                throw new IllegalArgumentException("too big");
            int length_bytes=(length>16384)?3:(length>128)?2:1;
            int needed=length+1+length_bytes;
            checkSpace(needed,blockFor);
            
            _buffer.put(frame);

            switch (length_bytes)
            {
                case 3:
                    _buffer.put((byte)(0x80|(length>>14)));
                case 2:
                    _buffer.put((byte)(0x80|(0x7f&(length>>7))));
                case 1:
                    _buffer.put((byte)(0x7f&length));
            }

            _buffer.put(content,offset,length);
        }
        else
        {
            // send in a sentinel frame
            int needed=length+2;
            checkSpace(needed,blockFor);

            _buffer.put(frame);
            _buffer.put(content,offset,length);
            _buffer.put((byte)0xFF);
        }
    }
    
    synchronized public void addFrame(byte frame, String content, int blockFor) throws IOException
    {
        Buffer byte_buffer=_buffers.getBuffer();
        try
        {
            byte[] array=byte_buffer.array();

            int chars = content.length();
            int bytes = 0;
            final int limit=array.length-6;

            for (int i = 0; i < chars; i++)
            {
                int code = content.charAt(i);

                if (bytes>=limit)
                    throw new IllegalArgumentException("frame too large");

                if ((code & 0xffffff80) == 0) 
                {
                    array[bytes++]=(byte)(code);
                }
                else if((code&0xfffff800)==0)
                {
                    array[bytes++]=(byte)(0xc0|(code>>6));
                    array[bytes++]=(byte)(0x80|(code&0x3f));
                }
                else if((code&0xffff0000)==0)
                {
                    array[bytes++]=(byte)(0xe0|(code>>12));
                    array[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                    array[bytes++]=(byte)(0x80|(code&0x3f));
                }
                else if((code&0xff200000)==0)
                {
                    array[bytes++]=(byte)(0xf0|(code>>18));
                    array[bytes++]=(byte)(0x80|((code>>12)&0x3f));
                    array[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                    array[bytes++]=(byte)(0x80|(code&0x3f));
                }
                else if((code&0xf4000000)==0)
                {
                    array[bytes++]=(byte)(0xf8|(code>>24));
                    array[bytes++]=(byte)(0x80|((code>>18)&0x3f));
                    array[bytes++]=(byte)(0x80|((code>>12)&0x3f));
                    array[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                    array[bytes++]=(byte)(0x80|(code&0x3f));
                }
                else if((code&0x80000000)==0)
                {
                    array[bytes++]=(byte)(0xfc|(code>>30));
                    array[bytes++]=(byte)(0x80|((code>>24)&0x3f));
                    array[bytes++]=(byte)(0x80|((code>>18)&0x3f));
                    array[bytes++]=(byte)(0x80|((code>>12)&0x3f));
                    array[bytes++]=(byte)(0x80|((code>>6)&0x3f));
                    array[bytes++]=(byte)(0x80|(code&0x3f));
                }
                else
                {
                    array[bytes++]=(byte)('?');
                }
            }
            addFrame(frame,array,0,bytes,blockFor);
        }
        finally
        {
            _buffers.returnBuffer(byte_buffer);
        }
    }
    
    private void checkSpace(int needed, long blockFor)
        throws IOException
    {
        int space=_buffer.space();
        
        if (space<needed)
        {
            if (_endp.isBlocking())
            {
                try
                {
                    flushBuffer();
                    _buffer.compact();
                    space=_buffer.space();
                }
                catch(IOException e)
                {
                    throw e;
                }
            }
            else
            {
                flushBuffer();
                _buffer.compact();
                space=_buffer.space();

                if (space<needed && _buffer.length()>0 && _endp.blockWritable(blockFor))
                {
                    flushBuffer();
                    _buffer.compact();
                    space=_buffer.space();
                }
            }
            
            if (space<needed)
            {
                _endp.close();
                throw new IOException("Full Timeout");
            }
        }
    }

    synchronized public int flush(long blockFor)
    {
        return 0;
    }

    synchronized public int flush() throws IOException
    {
        int flushed = flushBuffer();
        if (_buffer!=null && _buffer.length()==0)
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
