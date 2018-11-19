package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AsyncRequestLogWriter extends RequestLogWriter
{
    private static final Logger LOG = Log.getLogger(AsyncRequestLogWriter.class);
    private final BlockingQueue<String> _queue;
    private transient AsyncRequestLogWriter.WriterThread _thread;
    private boolean _warnedFull;

    public AsyncRequestLogWriter()
    {
        this(null, null);
    }

    public AsyncRequestLogWriter(String filename,BlockingQueue<String> queue)
    {
        super(filename);
        if (queue==null)
            queue=new BlockingArrayQueue<>(1024);
        _queue=queue;
    }

    private class WriterThread extends Thread
    {
        WriterThread()
        {
            setName("AsyncRequestLogWriter@"+Integer.toString(AsyncRequestLogWriter.this.hashCode(),16));
        }

        @Override
        public void run()
        {
            while (isRunning())
            {
                try
                {
                    String log = _queue.poll(10, TimeUnit.SECONDS);
                    if (log!=null)
                        AsyncRequestLogWriter.super.write(log);

                    while(!_queue.isEmpty())
                    {
                        log=_queue.poll();
                        if (log!=null)
                            AsyncRequestLogWriter.super.write(log);
                    }
                }
                catch (IOException e)
                {
                    LOG.warn(e);
                }
                catch (InterruptedException e)
                {
                    LOG.ignore(e);
                }
            }
        }
    }

    @Override
    protected synchronized void doStart() throws Exception
    {
        super.doStart();
        _thread = new AsyncRequestLogWriter.WriterThread();
        _thread.start();
    }

    @Override
    protected void doStop() throws Exception
    {
        _thread.interrupt();
        _thread.join();
        super.doStop();
        _thread=null;
    }

    @Override
    public void write(String log) throws IOException
    {
        if (!_queue.offer(log))
        {
            if (_warnedFull)
                LOG.warn("Log Queue overflow");
            _warnedFull=true;
        }
    }
}
