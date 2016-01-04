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

package org.eclipse.jetty.cdi.websocket;

import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * Abstract implementation of listener that needs both Container events and LifeCycle events
 */
public abstract class AbstractContainerListener implements LifeCycle.Listener, Container.InheritedListener
{
    @Override
    public void beanAdded(Container parent, Object child)
    {
        if (child instanceof LifeCycle)
        {
            ((LifeCycle)child).addLifeCycleListener(this);
        }
    }

    @Override
    public void beanRemoved(Container parent, Object child)
    {
        if (child instanceof LifeCycle)
        {
            ((LifeCycle)child).removeLifeCycleListener(this);
        }
    }

    @Override
    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {
    }

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
    }

    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
    }

    @Override
    public void lifeCycleStopped(LifeCycle event)
    {

    }

    @Override
    public void lifeCycleStopping(LifeCycle event)
    {
    }

}
