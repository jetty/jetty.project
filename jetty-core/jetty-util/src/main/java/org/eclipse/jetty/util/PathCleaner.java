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
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes filesystem paths that should not outlive the server, such as a secret-bearing
 * {@code @argument-file} generated at startup.
 * <p>
 * Cleanup is controlled by a {@link Mode}:
 * <ul>
 *   <li>{@link Mode#EARLY} - delete each path immediately (as soon as this is invoked, for example
 *       during XML configuration at startup).</li>
 *   <li>{@link Mode#LATE} - defer deletion to normal JVM shutdown via
 *       {@link java.io.File#deleteOnExit()} (this does not run on an abrupt kill such as SIGKILL).</li>
 *   <li>{@link Mode#NEVER} - leave the paths in place (useful for debugging).</li>
 * </ul>
 * Only existing regular files are removed; missing paths, directories, and other non-regular files
 * are ignored, and a failed deletion is logged rather than thrown, so cleanup never aborts server
 * startup.
 * <p>
 * It is typically driven from Jetty XML (see the {@code path-cleaner} module), which sets
 * {@link #setMode(String)} from the {@code jetty.path-cleanup.mode} property and calls
 * {@link #cleanPaths(String)} with the comma-separated {@code jetty.path-cleanup.files} property.
 */
public class PathCleaner
{
    private static final Logger LOG = LoggerFactory.getLogger(PathCleaner.class);

    enum Mode
    {
        EARLY,
        LATE,
        NEVER
    }

    private Mode mode = Mode.EARLY;

    public Mode getMode()
    {
        return mode;
    }

    /**
     * Set the cleanup mode from its name (case-insensitive).
     *
     * @param mode one of {@code EARLY}, {@code LATE}, or {@code NEVER} (case-insensitive)
     * @throws IllegalArgumentException if {@code mode} is null or not a recognized mode
     */
    public void setMode(String mode)
    {
        if (mode == null)
            throw new IllegalArgumentException("PathCleaner mode may not be null; expected one of EARLY, LATE, NEVER (property jetty.path-cleanup.mode)");
        try
        {
            this.mode = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException("Invalid PathCleaner mode '" + mode + "'; expected one of EARLY, LATE, NEVER (property jetty.path-cleanup.mode)", e);
        }
    }

    public void setMode(Mode mode)
    {
        this.mode = mode;
    }

    /**
     * Clean each path in a comma-separated list (see {@link #cleanPath(Path)}).
     *
     * @param paths comma-separated paths to clean; a null or blank value is ignored
     */
    public void cleanPaths(String paths)
    {
        if (StringUtil.isBlank(paths))
            return;

        String[] parts = StringUtil.csvSplit(paths);
        if (parts == null)
            return;

        for (String part: parts)
        {
            cleanPath(part);
        }
    }

    /**
     * Clean a single path (see {@link #cleanPath(Path)}).
     *
     * @param path the path to clean; a null or blank value is ignored
     */
    public void cleanPath(String path)
    {
        if (StringUtil.isBlank(path))
            return;
        cleanPath(Path.of(path));
    }

    /**
     * Clean a single path according to the configured {@link #getMode() mode}. Only an existing
     * regular file is acted on; a null, missing, directory, or other non-regular path is ignored,
     * and an {@code EARLY} delete that fails is logged rather than thrown.
     *
     * @param path the path to clean
     */
    public void cleanPath(Path path)
    {
        if (path == null)
            return;
        if (!Files.exists(path))
            return;
        if (!Files.isRegularFile(path))
        {
            LOG.warn("Unable to process path {} for cleanup, not a regular-file", path);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Performing Clean Mode:{} on path {}", mode, path);

        switch (mode)
        {
            case EARLY ->
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException e)
                {
                    if (LOG.isWarnEnabled())
                        LOG.warn("Unable to delete {}", path, e);
                }
            }
            case LATE ->
            {
                path.toFile().deleteOnExit();
            }
            case NEVER ->
            {
                // leave file alone.
            }
        }
    }

}
