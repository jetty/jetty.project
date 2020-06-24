//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A LifeCycle that when started will stop another LifeCycle
 */
public class StopLifeCycle extends AbstractLifeCycle implements LifeCycle.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(StopLifeCycle.class);

    private final LifeCycle _lifecycle;

    public StopLifeCycle(LifeCycle lifecycle)
    {
        _lifecycle = lifecycle;
        addEventListener(this);
    }

    @Override
    public void lifeCycleStarting(LifeCycle lifecycle)
    {
    }

    @Override
    public void lifeCycleStarted(LifeCycle lifecycle)
    {
        try
        {
            _lifecycle.stop();
        }
        catch (Exception e)
        {
            LOG.warn("Unable to stop", e);
        }
    }

    @Override
    public void lifeCycleFailure(LifeCycle lifecycle, Throwable cause)
    {
    }

    @Override
    public void lifeCycleStopping(LifeCycle lifecycle)
    {
    }

    @Override
    public void lifeCycleStopped(LifeCycle lifecycle)
    {
    }
}
