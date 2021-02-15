//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.component;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A LifeCycle that when started will stop another LifeCycle
 */
public class StopLifeCycle extends AbstractLifeCycle implements LifeCycle.Listener
{
    private static final Logger LOG = Log.getLogger(StopLifeCycle.class);

    private final LifeCycle _lifecycle;

    public StopLifeCycle(LifeCycle lifecycle)
    {
        _lifecycle = lifecycle;
        addLifeCycleListener(this);
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
            LOG.warn(e);
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
