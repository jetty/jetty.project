//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.common;

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
