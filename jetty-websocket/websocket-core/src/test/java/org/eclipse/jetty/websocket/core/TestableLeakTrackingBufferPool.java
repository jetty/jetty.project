//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class TestableLeakTrackingBufferPool extends LeakTrackingByteBufferPool
{
    private final String id;

    public TestableLeakTrackingBufferPool(Class<?> clazz)
    {
        this(clazz.getSimpleName());
    }

    public TestableLeakTrackingBufferPool(String id)
    {
        super(new MappedByteBufferPool.Tagged());
        this.id = id;
    }

    public void assertNoLeaks()
    {
        assertThat("Leaked Acquires Count for [" + id + "]", getLeakedAcquires(), is(0L));
        assertThat("Leaked Releases Count for [" + id + "]", getLeakedReleases(), is(0L));
        assertThat("Leaked Resource Count for [" + id + "]", getLeakedResources(), is(0L));
    }
}
