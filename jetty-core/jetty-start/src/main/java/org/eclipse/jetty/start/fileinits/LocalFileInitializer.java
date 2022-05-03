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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartLog;

public class LocalFileInitializer extends FileInitializer
{
    public LocalFileInitializer(BaseHome basehome)
    {
        super(basehome);
    }

    @Override
    public boolean create(URI uri, String location) throws IOException
    {
        Path destination = getDestination(uri, location);

        if (destination == null)
        {
            StartLog.error("Bad file arg %s", uri);
            return false;
        }

        boolean isDir = location.endsWith("/");

        if (FS.exists(destination))
        {
            // Validate existence
            if (isDir)
            {
                if (!Files.isDirectory(destination))
                {
                    throw new IOException("Invalid: path should be a directory (but isn't): " + location);
                }
                if (!FS.canReadDirectory(destination))
                {
                    throw new IOException("Unable to read directory: " + location);
                }
            }
            else
            {
                if (!FS.canReadFile(destination))
                {
                    throw new IOException("Unable to read file: " + location);
                }
            }

            return false;
        }

        if (isDir)
        {
            // Create directory
            boolean mkdir = FS.ensureDirectoryExists(destination);
            if (mkdir)
                StartLog.info("mkdir " + _basehome.toShortForm(destination));
            return mkdir;
        }

        throw new IOException("Unable to create " + _basehome.toShortForm(destination));
    }
}
