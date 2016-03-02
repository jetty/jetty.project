//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class LeakTrackingBufferPoolRule extends LeakTrackingByteBufferPool implements TestRule
{
    private final String id;

    public LeakTrackingBufferPoolRule(String id)
    {
        super(new MappedByteBufferPool.Tagged());
        this.id = id;
    }

    public void assertNoLeaks()
    {
        assertThat("Leaked Acquires Count for [" + id + "]",getLeakedAcquires(),is(0L));
        assertThat("Leaked Releases Count for [" + id + "]",getLeakedReleases(),is(0L));
        assertThat("Leaked Resource Count for [" + id + "]", getLeakedResources(),is(0L));
    }

    @Override
    public Statement apply(final Statement statement, Description description)
    {
        return new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                clearTracking();
                statement.evaluate();
                assertNoLeaks();
            }
        };
    }
}
