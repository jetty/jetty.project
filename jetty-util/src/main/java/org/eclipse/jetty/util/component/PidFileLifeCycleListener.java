//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PidFileLifeCycleListener implements LifeCycle.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(PidFileLifeCycleListener.class);

    private final Path pidFile;

    public PidFileLifeCycleListener(String filename)
    {
        pidFile = Paths.get(filename);
    }

    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
        long pid = ProcessHandle.current().pid();
        try
        {
            Files.writeString(pidFile, Long.toString(pid), StandardCharsets.UTF_8);
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to create pidFile: {}", pidFile, t);
        }
    }

    @Override
    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {
        removePid();
    }

    @Override
    public void lifeCycleStopped(LifeCycle event)
    {
        removePid();
    }

    private void removePid()
    {
        try
        {
            Files.deleteIfExists(pidFile);
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to remove pidFile: {}", pidFile, t);
        }
    }
}
