// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;

import org.eclipse.jetty.io.AsyncByteArrayEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class LocalHttpConnector extends HttpConnector
{
    private static final Logger LOG = Log.getLogger(LocalHttpConnector.class);
    private final BlockingQueue<LocalEndPoint> _connects = new LinkedBlockingQueue<LocalEndPoint>();
    private LocalExecutor _executor;
    
    public LocalHttpConnector()
    {
        setMaxIdleTime(30000);
    }

    @Override
    public Object getTransport()
    {
        return this;
    }

    public String getResponses(String requests) throws Exception
    {
        ByteBuffer result = getResponses(BufferUtil.toBuffer(requests,StringUtil.__UTF8_CHARSET));
        return result==null?null:BufferUtil.toString(result,StringUtil.__UTF8_CHARSET);
    }

    public ByteBuffer getResponses(ByteBuffer requestsBuffer) throws Exception
    {
        int phase=_executor._phaser.getPhase();
        LocalEndPoint request = new LocalEndPoint();
        request.setInput(requestsBuffer);
        _connects.add(request);
        _executor._phaser.awaitAdvance(phase);
        return request.takeOutput();
    }

    public void executeRequest(String rawRequest)
    {
        LocalEndPoint endp = new LocalEndPoint();
        endp.setInput(BufferUtil.toBuffer(rawRequest,StringUtil.__UTF8_CHARSET));
        _connects.add(endp);
    }
    
    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        LOG.debug("accepting {}",acceptorID);
        LocalEndPoint endp = _connects.take();
        _executor._phaser.register();
        HttpConnection connection=new HttpConnection(this,endp,getServer());
        endp.setAsyncConnection(connection);
        LOG.debug("accepted {} {}",endp,connection);
        connection.onOpen();
        _executor._phaser.arriveAndDeregister();
    }
    
    
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        _executor=new LocalExecutor(findExecutor());
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _executor=null;
    }

    @Override
    public Executor findExecutor()
    {
        return _executor==null?super.findExecutor():_executor;
    }

    class LocalExecutor implements Executor
    {
        Phaser _phaser=new Phaser()
        {

            @Override
            protected boolean onAdvance(int phase, int registeredParties)
            {
                return super.onAdvance(phase,registeredParties);
            }
            
        };
        final Executor _executor;
        LocalExecutor(Executor e)
        {
            _executor=e;
        }
        
        @Override
        public void execute(final Runnable task)
        {
            _phaser.register();
            _executor.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        task.run();
                    }
                    finally
                    {
                        _phaser.arriveAndDeregister();
                    }
                }   
            });
        }
    }
    
    private class LocalEndPoint extends AsyncByteArrayEndPoint
    {
        LocalEndPoint()
        {
            setGrowOutput(true);
        }
        
        LocalEndPoint(CountDownLatch onCloseLatch)
        {
            this();
        }
    }    
    
}
