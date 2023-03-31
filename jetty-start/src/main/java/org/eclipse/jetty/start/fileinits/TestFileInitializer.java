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

package org.eclipse.jetty.start.fileinits;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.FileInitializer;
import org.eclipse.jetty.start.StartLog;

/**
 * In a start testing scenario, it is often not important to actually download
 * or initialize a file, this implementation is merely a no-op for the
 * {@link FileInitializer}
 */
public class TestFileInitializer extends FileInitializer
{
    public TestFileInitializer(BaseHome basehome)
    {
        super(basehome);
    }

    @Override
    public boolean isApplicable(URI uri)
    {
        return true;
    }

    @Override
    public boolean create(URI uri, String location) throws IOException
    {
        Path destination = getDestination(uri, location);
        if (destination != null)
        {
            if (location.endsWith("/"))
                FS.ensureDirectoryExists(destination);
            else
                FS.ensureDirectoryExists(destination.getParent());
        }

        StartLog.info("Skipping download of %s", uri);
        return true;
    }
}
