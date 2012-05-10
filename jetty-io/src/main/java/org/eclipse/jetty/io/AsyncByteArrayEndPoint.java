package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AsyncByteArrayEndPoint extends ByteArrayEndPoint implements AsyncEndPoint
{
    public static final Logger LOG=Log.getLogger(AsyncByteArrayEndPoint.class);
    private static final Timer _timer = new Timer();
    private boolean _checkForIdle;
    private AsyncConnection _connection;
    
    private final ReadInterest _readInterest = new ReadInterest()
    {
        @Override
        protected boolean readIsPossible() throws IOException
        {
            if (_closed)
                throw new ClosedChannelException();
            return _in==null || BufferUtil.hasContent(_in);
        }       
    };
    
    private final WriteFlusher _writeFlusher = new WriteFlusher(this)
    {
        @Override
        protected void scheduleCompleteWrite()
        {            
        }
    };
    
    public AsyncByteArrayEndPoint()
    {
        super();
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
    public <C> void readable(C context, Callback<C> callback) throws IllegalStateException
    {
        _readInterest.readable(context,callback);
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
    {
        _writeFlusher.write(context,callback,buffers);
    }

    @Override
    public void setCheckForIdle(boolean check)
    {
        _checkForIdle=check;
        
        
        if (check)
        {
            final TimerTask task=new TimerTask()
            {
                @Override
                public void run()
                {
                    checkForIdleOrReadWriteTimeout(System.currentTimeMillis());
                    if (_checkForIdle)
                        _timer.schedule(this,1000);
                }
            };
            
            _timer.schedule(task,1000);
        }
    }

    @Override
    public boolean isCheckForIdle()
    {
        return _checkForIdle;
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
    public void checkForIdleOrReadWriteTimeout(long now)
    {
        synchronized (this)
        {
            if (_checkForIdle || _readInterest.isInterested() || _writeFlusher.isWriting())
            {
                long idleTimestamp = getIdleTimestamp();
                long max_idle_time = getMaxIdleTime();

                if (idleTimestamp != 0 && max_idle_time > 0)
                {
                    long idleForMs = now - idleTimestamp;

                    if (idleForMs > max_idle_time)
                    {
                        notIdle();
                        
                        if (_checkForIdle)
                            _connection.onIdleExpired(idleForMs);
                        
                        TimeoutException timeout = new TimeoutException();
                        _readInterest.failed(timeout);
                        _writeFlusher.failed(timeout);
                    }
                }
            }
        }
    }

    @Override
    public void onClose()
    {
        setCheckForIdle(false);
        super.onClose();
    }
    
}
