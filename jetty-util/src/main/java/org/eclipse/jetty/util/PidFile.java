//
// ========================================================================
// Copyright (c) 1995-2023 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.ShutdownThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Create a PID file for the running process.
 */
public class PidFile extends AbstractLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(PidFile.class);

    private final Path pidFile;

    public PidFile(@Name("file") String filename)
    {
        pidFile = Paths.get(filename);
        try
        {
            // Create the PID file as soon as possible.
            // We don't want for doStart() as we want the PID creation to occur quickly for jetty.sh
            long pid = ProcessHandle.current().pid();
            Files.writeString(pidFile, Long.toString(pid), UTF_8, CREATE, WRITE, TRUNCATE_EXISTING);
            ShutdownThread.register(this);
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
