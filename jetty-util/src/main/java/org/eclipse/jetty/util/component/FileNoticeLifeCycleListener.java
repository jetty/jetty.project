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

import java.io.FileWriter;
import java.io.Writer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A LifeCycle Listener that writes state changes to a file.
 * <p>This can be used with the jetty.sh script to wait for successful startup.
 */
public class FileNoticeLifeCycleListener implements LifeCycle.Listener
{
    private static final Logger LOG = Log.getLogger(FileNoticeLifeCycleListener.class);

    private final String _filename;

    public FileNoticeLifeCycleListener(String filename)
    {
        _filename = filename;
    }

    private void writeState(String action, LifeCycle lifecycle)
    {
        try (Writer out = new FileWriter(_filename, true))
        {
            out.append(action).append(" ").append(lifecycle.toString()).append("\n");
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
        writeState("STARTING", event);
    }

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        writeState("STARTED", event);
    }

    @Override
    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {
        writeState("FAILED", event);
    }

    @Override
    public void lifeCycleStopping(LifeCycle event)
    {
        writeState("STOPPING", event);
    }

    @Override
    public void lifeCycleStopped(LifeCycle event)
    {
        writeState("STOPPED", event);
    }
}
