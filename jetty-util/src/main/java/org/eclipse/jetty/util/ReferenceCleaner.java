//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Using {@link java.lang.ref.Cleaner} to perform actions when a reference is garbage collected.
 */
public final class ReferenceCleaner
{
    private static final Logger LOG = LoggerFactory.getLogger(ReferenceCleaner.class);
    private static final Cleaner CLEANER = Cleaner.create();

    public static void register(Object obj, Runnable action)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("register({}, {})", obj, action);
        CLEANER.register(obj, action);
    }

    /**
     * Action to delete a file once the referenced objects (that might hold a file lock) are released.
     */
    public static class DeleteFile implements Runnable
    {
        private final Path file;

        public DeleteFile(Path file)
        {
            this.file = file;
        }

        @Override
        public void run()
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("DeleteFile {}", file);
                Files.deleteIfExists(file);
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[file='%s']", this.getClass().getSimpleName(), this.hashCode(), file);
        }
    }
}
