package org.eclipse.jetty.websocket;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;




/* ------------------------------------------------------------ */
/** 
 * Parser the WebSocket protocol.
 * 
 */
public class WebSocketParser
{
    public static final int STATE_START=0;
    public static final int STATE_SENTINEL_DATA=1;
    public static final int STATE_LENGTH=2;
    public static final int STATE_DATA=3;

    private final WebSocketBuffers _buffers;
    private final EndPoint _endp;
    private final EventHandler _handler;
    private int _state;
    private Buffer _buffer;
    private byte _frame;
    private int _length;
    private Utf8StringBuilder _utf8;

    /* ------------------------------------------------------------ */
    /**
     * @param buffers The buffers to use for parsing.  Only the {@link Buffers#getBuffer()} is used.
     * This should be a direct buffer if binary data is mostly used or an indirect buffer if utf-8 data 
     * is mostly used.
     * @param endp
     * @param handler
     */
    public WebSocketParser(WebSocketBuffers buffers, EndPoint endp, EventHandler handler)
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
     * Parse to the next {@link EventHandler} event or until no more data is 
     * available. Fill data from the {@link EndPoint} only as necessary.
     * @return total bytes filled or -1 for EOF
     */
    public int parseNext()
    {
        if (_buffer==null)
            _buffer=_buffers.getBuffer();
        
        int total_filled=0;

        // Loop until an datagram call back or can't fill anymore
        boolean progress=true;
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
                        return total_filled;
                    total_filled+=filled;
                    length=_buffer.length();
                }
                catch(IOException e)
                {
                    Log.debug(e);
                    return total_filled>0?total_filled:-1;
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
                        _frame=b;
                        if (_frame<0)
                        {
                            _length=0;
                            _state=STATE_LENGTH;
                        }
                        else
                        {
                            if (_utf8==null)
                                _utf8=new Utf8StringBuilder();
                            _state=STATE_SENTINEL_DATA;
                            _buffer.mark();
                        }
                        continue;

                    case STATE_SENTINEL_DATA:
                        b=_buffer.get();
                        if ((b&0xff)==0xff)
                        {
                            String data=_utf8.toString();
                            _utf8.reset();
                            _state=STATE_START;
                            _handler.onFrame(_frame,data);
                            _buffer.setMarkIndex(-1);
                            if (_buffer.length()==0)
                            {
                                _buffers.returnBuffer(_buffer);
                                _buffer=null;
                            }
                            return total_filled;
                        }
                        _utf8.append(b);
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
                        _handler.onFrame(_frame,data);
                        
                        if (_buffer.length()==0)
                        {
                            _buffers.returnBuffer(_buffer);
                            _buffer=null;
                        }
                        
                        return total_filled;
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
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface EventHandler
    {
        void onFrame(byte frame,String data);
        void onFrame(byte frame,Buffer buffer);
    }

}
