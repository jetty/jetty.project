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

package org.eclipse.jetty.util.component;

import java.io.FileWriter;
import java.io.Writer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A LifeCycle Listener that writes state changes to a file.
 * <p>This can be used with the jetty.sh script to wait for successful startup.
 */
public class FileNoticeLifeCycleListener implements LifeCycle.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(FileNoticeLifeCycleListener.class);

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
            LOG.warn("Unable to write state", e);
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
