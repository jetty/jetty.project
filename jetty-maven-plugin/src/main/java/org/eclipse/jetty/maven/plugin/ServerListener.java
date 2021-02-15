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

package org.eclipse.jetty.maven.plugin;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;

/**
 * ServerListener
 *
 * Listener to create a file that signals that the startup is completed.
 * Used by the JettyRunDistro maven goal to determine that the child
 * process is started, and that jetty is ready.
 */
public class ServerListener implements LifeCycle.Listener
{

    private String _tokenFile;

    public void setTokenFile(String file)
    {
        _tokenFile = file;
    }

    public String getTokenFile()
    {
        return _tokenFile;
    }

    /**
     * @see org.eclipse.jetty.util.component.LifeCycle.Listener#lifeCycleStarting(org.eclipse.jetty.util.component.LifeCycle)
     */
    @Override
    public void lifeCycleStarting(LifeCycle event)
    {

    }

    /**
     * @see org.eclipse.jetty.util.component.LifeCycle.Listener#lifeCycleStarted(org.eclipse.jetty.util.component.LifeCycle)
     */
    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        if (_tokenFile != null)
        {
            try
            {
                Resource r = Resource.newResource(_tokenFile);
                r.getFile().createNewFile();
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * @see org.eclipse.jetty.util.component.LifeCycle.Listener#lifeCycleFailure(org.eclipse.jetty.util.component.LifeCycle, java.lang.Throwable)
     */
    @Override
    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {

    }

    /**
     * @see org.eclipse.jetty.util.component.LifeCycle.Listener#lifeCycleStopping(org.eclipse.jetty.util.component.LifeCycle)
     */
    @Override
    public void lifeCycleStopping(LifeCycle event)
    {

    }

    /**
     * @see org.eclipse.jetty.util.component.LifeCycle.Listener#lifeCycleStopped(org.eclipse.jetty.util.component.LifeCycle)
     */
    @Override
    public void lifeCycleStopped(LifeCycle event)
    {

    }
}
