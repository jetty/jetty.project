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

package org.eclipse.jetty.maven.plugin;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;

/**
 * ServerListener
 *
 * Listener to create a file that signals that the startup is completed.
 * Used by the JettyRunHome maven goal to determine that the child
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

    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
    }

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

    @Override
    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {
    }

    @Override
    public void lifeCycleStopping(LifeCycle event)
    {
    }

    @Override
    public void lifeCycleStopped(LifeCycle event)
    {
    }
}
