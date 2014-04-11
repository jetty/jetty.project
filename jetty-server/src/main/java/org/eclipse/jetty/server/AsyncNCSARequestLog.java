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

package org.eclipse.jetty.server;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/* ------------------------------------------------------------ */
/**
 * An asynchronously writing NCSA Request Log
 */
public class AsyncNCSARequestLog extends NCSARequestLog
{
    private static final Logger LOG = Log.getLogger(AsyncNCSARequestLog.class);
    private final BlockingQueue<String> _queue;
    private transient WriterThread _thread;
    private boolean _warnedFull;

    public AsyncNCSARequestLog()
    {
        this(null,null);
    }
    
    public AsyncNCSARequestLog(BlockingQueue<String> queue)
    {
        this(null,queue);
    }

    public AsyncNCSARequestLog(String filename)
    {
        this(filename,null);
    }
    
    public AsyncNCSARequestLog(String filename,BlockingQueue<String> queue)
    {
        super(filename);
        if (queue==null)
            queue=new BlockingArrayQueue<String>(1024);
        _queue=queue;
    }

    private class WriterThread extends Thread
    {
        WriterThread()
        {
            setName("AsyncNCSARequestLog@"+Integer.toString(AsyncNCSARequestLog.this.hashCode(),16));
        }
        
        @Override
        public void run()
        {
            while (isRunning())
            {
                try
                {
                    String log = _queue.poll(10,TimeUnit.SECONDS);
                    if (log!=null)
                        AsyncNCSARequestLog.super.write(log);
                    
                    while(!_queue.isEmpty())
                    {
                        log=_queue.poll();
                        if (log!=null)
                            AsyncNCSARequestLog.super.write(log);
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
        _thread = new WriterThread();
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
    protected void write(String log) throws IOException
    {
        if (!_queue.offer(log))
        {
            if (_warnedFull)
                LOG.warn("Log Queue overflow");
            _warnedFull=true;
        }
    }

}
