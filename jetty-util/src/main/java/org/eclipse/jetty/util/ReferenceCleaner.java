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

import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Using {@link java.lang.ref.Cleaner} to perform actions when a reference is garbage collected.
 */
public final class ReferenceCleaner
{
    private static final Logger LOG = LoggerFactory.getLogger(ReferenceCleaner.class);
    private static final Cleaner CLEANER = Cleaner.create();

    public static CompletableFuture<Void> register(Object obj, Runnable action)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("register({}, {})", obj, action);
        CompletableFuture<Void> fut = new CompletableFuture<>();
        CLEANER.register(obj, () ->
        {
            try
            {
                action.run();
                fut.complete(null);
            }
            catch (Throwable t)
            {
                fut.completeExceptionally(t);
            }
        });
        return fut;
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

        public static void delete(Path file)
        {
            if (file == null)
                return;
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("DeleteFile {}", file);
                Files.deleteIfExists(file);
            }
            catch (Throwable t)
            {
                LOG.trace("IGNORED", t);
            }
        }

        @Override
        public void run()
        {
            delete(file);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[file='%s']", getClass().getSimpleName(), hashCode(), file);
        }
    }
}
