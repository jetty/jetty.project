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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.annotation.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Create a PID file for the running process.
 *
 * <p>
 *     Will register itself in a {@link Runtime#addShutdownHook(Thread)}
 *     to cleanup the PID file it created during normal JVM shutdown.
 * </p>
 */
public class PidFile extends Thread
{
    private static final Logger LOG = LoggerFactory.getLogger(PidFile.class);
    private static final Set<Path> activeFiles = ConcurrentHashMap.newKeySet();

    public static void create(@Name("file") String filename) throws IOException
    {
        Path pidFile = Paths.get(filename).toAbsolutePath();

        if (activeFiles.add(pidFile))
        {
            Runtime.getRuntime().addShutdownHook(new PidFile(pidFile));

            if (Files.exists(pidFile))
                LOG.info("Overwriting existing PID file: {}", pidFile);

            // Create the PID file as soon as possible.
            long pid = ProcessHandle.current().pid();
            Files.writeString(pidFile, Long.toString(pid), UTF_8, CREATE, WRITE, TRUNCATE_EXISTING);
            if (LOG.isDebugEnabled())
                LOG.debug("PID file: {}", pidFile);
        }
    }

    private final Path pidFile;

    private PidFile(Path pidFile)
    {
        this.pidFile = pidFile;
    }

    @Override
    public void run()
    {
        try
        {
            Files.deleteIfExists(pidFile);
        }
        catch (Throwable t)
        {
            LOG.info("Unable to remove PID file: {}", pidFile, t);
        }
    }
}
