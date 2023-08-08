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

package org.eclipse.jetty.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PidFile extends AbstractLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(PidFile.class);

    private final Path pidFile;

    public PidFile(@Name("file") String filename)
    {
        pidFile = Paths.get(filename);
        try
        {
            long pid = ProcessHandle.current().pid();
            Files.writeString(pidFile, Long.toString(pid), StandardCharsets.UTF_8);
            ShutdownMonitor.register(this);
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to create pidFile: {}", pidFile, t);
        }
    }

    @Override
    protected void doStop() throws Exception
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
