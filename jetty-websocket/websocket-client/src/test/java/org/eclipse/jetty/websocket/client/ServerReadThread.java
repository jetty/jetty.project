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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadServer.ServerConnection;
import org.junit.Assert;

public class ServerReadThread extends Thread
{
    private static final int BUFFER_SIZE = 8192;
    private static final Logger LOG = Log.getLogger(ServerReadThread.class);
    private final ServerConnection conn;
    private boolean active = true;
    private int slowness = -1; // disabled is default
    private AtomicInteger frameCount = new AtomicInteger();
    private CountDownLatch expectedMessageCount;

    public ServerReadThread(ServerConnection conn)
    {
        this.conn = conn;
        this.expectedMessageCount = new CountDownLatch(1);
    }

    public void cancel()
    {
        active = false;
    }

    public int getFrameCount()
    {
        return frameCount.get();
    }

    public int getSlowness()
    {
        return slowness;
    }

    @Override
    public void run()
    {
        ByteBufferPool bufferPool = conn.getBufferPool();
        ByteBuffer buf = bufferPool.acquire(BUFFER_SIZE,false);
        BufferUtil.clearToFill(buf);

        int len = 0;

        try
        {
            while (active)
            {
                BufferUtil.clearToFill(buf);
                len = conn.read(buf);

                if (len > 0)
                {
                    LOG.debug("Read {} bytes",len);
                    BufferUtil.flipToFlush(buf,0);
                    conn.getParser().parse(buf);
                }

                Queue<WebSocketFrame> frames = conn.getIncomingFrames().getFrames();
                WebSocketFrame frame;
                while ((frame = frames.poll()) != null)
                {
                    frameCount.incrementAndGet();
                    if (frame.getOpCode() == OpCode.CLOSE)
                    {
                        active = false;
                        // automatically response to close frame
                        CloseInfo close = new CloseInfo(frame);
                        conn.close(close.getStatusCode());
                    }

                    expectedMessageCount.countDown();
                }
                if (slowness > 0)
                {
                    TimeUnit.MILLISECONDS.sleep(slowness);
                }
            }
        }
        catch (IOException | InterruptedException e)
        {
            LOG.warn(e);
        }
        finally
        {
            bufferPool.release(buf);
        }
    }

    public void setExpectedMessageCount(int expectedMessageCount)
    {
        this.expectedMessageCount = new CountDownLatch(expectedMessageCount);
    }

    public void setSlowness(int slowness)
    {
        this.slowness = slowness;
    }

    public void waitForExpectedMessageCount(int timeoutDuration, TimeUnit timeoutUnit) throws InterruptedException
    {
        Assert.assertThat("Expected Message Count attained",expectedMessageCount.await(timeoutDuration,timeoutUnit),is(true));
    }
}
