package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.math.BigInteger;

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

    public synchronized void addFrame(byte frame,byte[] content, int blockFor) throws IOException
    {
        addFrame(frame,content,0,content.length,blockFor);
    }

    public synchronized void addFrame(byte frame,byte[] content, int offset, int length, int blockFor) throws IOException
    {
        if (_buffer==null)
            _buffer=_buffers.getDirectBuffer();

        if (_buffer.space() == 0)
            expelBuffer(blockFor);

        if ((frame & WebSocket.LENGTH_FRAME) == WebSocket.LENGTH_FRAME)
        {
            // Send a length delimited frame

            _buffer.put(frame);
            if (_buffer.space() == 0)
                expelBuffer(blockFor);

            // How many bytes we need for the length ?
            // We have 7 bits available, so log2(length) / 7 + 1
            // For example, 50000 bytes is 2 8-bytes: 11000011 01010000
            // but we need to write it in 3 7-bytes 0000011 0000110 1010000
            // 65536 == 1 00000000 00000000 => 100 0000000 0000000
            int lengthBytes = new BigInteger(String.valueOf(length)).bitLength() / 7 + 1;
            for (int i = lengthBytes - 1; i >= 0; --i)
            {
                byte lengthByte = (byte)(0x80 | (0x7F & (length >> 7 * i)));
                _buffer.put(lengthByte);
                if (_buffer.space() == 0)
                    expelBuffer(blockFor);
            }
        }
        else
        {
            _buffer.put(frame);
        }

        if (_buffer.space() == 0)
            expelBuffer(blockFor);

        int remaining = length;
        while (remaining > 0)
        {
            int chunk = remaining < _buffer.space() ? remaining : _buffer.space();
            _buffer.put(content, offset + (length - remaining), chunk);
            remaining -= chunk;
            if (_buffer.space() > 0)
            {
                if (frame == WebSocket.SENTINEL_FRAME)
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
                    if (frame == WebSocket.SENTINEL_FRAME)
                        _buffer.put((byte)0xFF);
                    // Gently flush the data, issuing a non-blocking write
                    flushBuffer();
                }
            }
        }
    }

    public synchronized void addFrame(byte frame, String content, int blockFor) throws IOException
    {
        byte[] bytes = content.getBytes("UTF-8");
        addFrame(frame, bytes, 0, bytes.length, blockFor);
    }

    private synchronized void checkSpace(int needed, long blockFor)
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

    public synchronized int flush(long blockFor)
    {
        return 0;
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
            throw new IOException("Closed");

        if (_buffer!=null)
        {
            int flushed =_endp.flush(_buffer);
            if (flushed>0)
                _buffer.skip(flushed);
            return flushed;
        }
        return 0;
    }

    private synchronized void expelBuffer(long blockFor) throws IOException
    {
        flushBuffer();
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

                flushBuffer();
                _buffer.compact();
            }
        }
    }

    public synchronized boolean isBufferEmpty()
    {
        return _buffer==null || _buffer.length()==0;
    }
}
