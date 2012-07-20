package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AsyncByteArrayEndPoint extends ByteArrayEndPoint implements AsyncEndPoint
{
    public static final Logger LOG=Log.getLogger(AsyncByteArrayEndPoint.class);
    private volatile AsyncConnection _connection;
    private final ReadInterest _readInterest = new ReadInterest()
    {
        @Override
        protected boolean needsFill() throws IOException
        {
            if (_closed)
                throw new ClosedChannelException();
            return _in==null || BufferUtil.hasContent(_in);
        }
    };
    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void onIncompleteFlushed()
        {
            // Don't need to do anything here as takeOutput does the signalling.
        }
    };

    public AsyncByteArrayEndPoint()
    {
    }

    public AsyncByteArrayEndPoint(byte[] input, int outputSize)
    {
        super(input,outputSize);
    }

    public AsyncByteArrayEndPoint(String input, int outputSize)
    {
        super(input,outputSize);
    }

    @Override
    public void setInput(ByteBuffer in)
    {
        super.setInput(in);
        if (in==null || BufferUtil.hasContent(in))
            _readInterest.readable();
    }

    @Override
    public ByteBuffer takeOutput()
    {
        ByteBuffer b = super.takeOutput();
        _writeFlusher.completeWrite();
        return b;
    }

    @Override
    public void setOutput(ByteBuffer out)
    {
        super.setOutput(out);
        _writeFlusher.completeWrite();
    }

    @Override
    public void reset()
    {
        _readInterest.close();
        _writeFlusher.close();
        super.reset();
    }

    @Override
    public <C> void fillInterested(C context, Callback<C> callback) throws IllegalStateException
    {
        _readInterest.register(context,callback);
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
    {
        _writeFlusher.write(context,callback,buffers);
    }

    @Override
    public AsyncConnection getAsyncConnection()
    {
        return _connection;
    }

    @Override
    public void setAsyncConnection(AsyncConnection connection)
    {
        _connection=connection;
    }

    @Override
    public void checkTimeout(long now)
    {
        synchronized (this)
        {
            if (isOutputShutdown() || _readInterest.isInterested() || _writeFlusher.isWriting())
            {
                long idleTimestamp = getIdleTimestamp();
                long max_idle_time = getMaxIdleTime();

                if (idleTimestamp != 0 && max_idle_time > 0)
                {
                    long idleForMs = now - idleTimestamp;

                    if (idleForMs > max_idle_time)
                    {
                        if (isOutputShutdown())
                            close();
                        notIdle();

                        TimeoutException timeout = new TimeoutException("idle "+idleForMs+"ms");
                        _readInterest.failed(timeout);
                        _writeFlusher.failed(timeout);
                    }
                }
            }
        }
    }

    @Override
    public void onOpen()
    {
    }

    @Override
    public void onClose()
    {
    }
}
