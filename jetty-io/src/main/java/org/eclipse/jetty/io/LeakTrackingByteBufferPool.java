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
import java.util.concurrent.atomic.AtomicLong;

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
        public String id(ByteBuffer resource) 
        {
            return BufferUtil.toIDString(resource);
        }
    };

    private final static boolean NOISY = Boolean.getBoolean(LeakTrackingByteBufferPool.class.getName() + ".NOISY");
    private final ByteBufferPool delegate;
    private final AtomicLong leakedReleases = new AtomicLong(0);
    private final AtomicLong leakedAcquires = new AtomicLong(0);

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
        boolean leaked = leakDetector.acquired(buffer);
        if (NOISY || !leaked)
        {
            leakedAcquires.incrementAndGet();
            LOG.info(String.format("ByteBuffer acquire %s leaked.acquired=%s",leakDetector.id(buffer),leaked ? "normal" : "LEAK"),
                    new Throwable("LeakStack.Acquire"));
        }
        return buffer;
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer == null)
            return;
        boolean leaked = leakDetector.released(buffer);
        if (NOISY || !leaked) {
            leakedReleases.incrementAndGet();
            LOG.info(String.format("ByteBuffer release %s leaked.released=%s",leakDetector.id(buffer),leaked ? "normal" : "LEAK"),new Throwable(
                    "LeakStack.Release"));
        }
        delegate.release(buffer);
    }
    
    public void clearTracking()
    {
        leakDetector.clear();
        leakedAcquires.set(0);
        leakedReleases.set(0);
    }
    
    /**
     * Get the count of BufferPool.acquire() calls that detected a leak
     * @return count of BufferPool.acquire() calls that detected a leak
     */
    public long getLeakedAcquires()
    {
        return leakedAcquires.get();
    }
    
    /**
     * Get the count of BufferPool.release() calls that detected a leak
     * @return count of BufferPool.release() calls that detected a leak
     */
    public long getLeakedReleases()
    {
        return leakedReleases.get();
    }
    
    /**
     * At the end of the run, when the LeakDetector runs, this reports the
     * number of unreleased resources.
     * @return count of resources that were acquired but not released (byt the end of the run)
     */
    public long getLeakedUnreleased()
    {
        return leakDetector.getUnreleasedCount();
    }
}
