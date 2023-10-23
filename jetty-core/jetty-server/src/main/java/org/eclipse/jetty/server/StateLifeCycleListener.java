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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * A LifeCycle Listener that writes state changes to a file.
 * <p>This can be used with the jetty.sh script to wait for successful startup.
 */
public class StateLifeCycleListener implements LifeCycle.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(StateLifeCycleListener.class);

    private final Path stateFile;

    public StateLifeCycleListener(String filename) throws IOException
    {
        stateFile = Paths.get(filename).toAbsolutePath();

        if (LOG.isDebugEnabled())
            LOG.debug("State File: {}", stateFile);

        // We use raw Files APIs here to allow important IOExceptions
        // to fail the startup of Jetty, as these kinds of errors
        // point to filesystem permission issues that must be resolved
        // by the user before the state file can be used.

        // Start with fresh file (for permission reasons)
        Files.deleteIfExists(stateFile);

        // Create file
        Files.writeString(stateFile, "INIT " + this + "\n", UTF_8, WRITE, CREATE_NEW);
    }

    private void appendStateChange(String action, Object obj)
    {
        try (Writer out = Files.newBufferedWriter(stateFile, UTF_8, WRITE, APPEND))
        {
            String entry = String.format("%s %s\n", action, obj);
            if (LOG.isDebugEnabled())
                LOG.debug("appendEntry to {}: {}", stateFile, entry);
            out.append(entry);
        }
        catch (IOException e)
        {
            // this can happen if the uid of the Jetty process changes after it has been started
            // such as can happen with some setuid configurations
            LOG.warn("Unable to append to state file: " + stateFile, e);
        }
    }

    @Override
    public void lifeCycleStarting(LifeCycle event)
    {
        appendStateChange("STARTING", event);
    }

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        appendStateChange("STARTED", event);
    }

    @Override
    public void lifeCycleFailure(LifeCycle event, Throwable cause)
    {
        appendStateChange("FAILED", event);
    }

    @Override
    public void lifeCycleStopping(LifeCycle event)
    {
        appendStateChange("STOPPING", event);
    }

    @Override
    public void lifeCycleStopped(LifeCycle event)
    {
        appendStateChange("STOPPED", event);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%h", this.getClass().getSimpleName(), this);
    }
}
