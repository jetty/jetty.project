//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class LeakTrackingByteBufferPool extends ContainerLifeCycle implements ByteBufferPool
{
    private static final Logger LOG = Log.getLogger(LeakTrackingByteBufferPool.class);

    private final LeakDetector<ByteBuffer> leakDetector = new LeakDetector<ByteBuffer>()
    {
        @Override
        protected void leaked(LeakInfo leakInfo)
        {
            LeakTrackingByteBufferPool.this.leaked(leakInfo);
        }
    };

    private final static boolean NOISY = Boolean.getBoolean(LeakTrackingByteBufferPool.class.getName() + ".NOISY");
    private final ByteBufferPool delegate;

    public LeakTrackingByteBufferPool(ByteBufferPool delegate)
    {
        this.delegate = delegate;
        addBean(leakDetector);
        addBean(delegate);
    }

    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        ByteBuffer buffer = delegate.acquire(size,direct);
        boolean leakd = leakDetector.acquired(buffer);
        if (NOISY || !leakd)
            LOG.info(String.format("ByteBuffer acquire %s leakd.acquired=%s",BufferUtil.toIDString(buffer),leakd ? "normal" : "LEAK"),
                    new Throwable("LeakStack.Acquire"));
        return buffer;
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer == null)
            return;
        boolean leakd = leakDetector.released(buffer);
        if (NOISY || !leakd)
            LOG.info(String.format("ByteBuffer release %s leakd.released=%s",BufferUtil.toIDString(buffer),leakd ? "normal" : "LEAK"),
                    new Throwable("LeakStack.Release"));
        delegate.release(buffer);
    }

    protected void leaked(LeakDetector<ByteBuffer>.LeakInfo leakInfo)
    {
        LOG.warn("ByteBuffer " + leakInfo.getResourceDescription() + " leaked at:",leakInfo.getStackFrames());
    }
}
