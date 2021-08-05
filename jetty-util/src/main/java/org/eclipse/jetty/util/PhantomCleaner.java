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
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PhantomCleaner
{
    private static final Logger LOG = LoggerFactory.getLogger(PhantomCleaner.class);
    private static final Cleaner CLEANER = Cleaner.create();

    public static void register(Object obj, Runnable action)
    {
        CLEANER.register(obj, action);
    }

    public static class FileDispose implements Runnable
    {
        private final String filename;

        public FileDispose(Path file)
        {
            // storing this reference as a String to allow GC to work.
            this.filename = file.toAbsolutePath().toString();
        }

        @Override
        public void run()
        {
            Path file = Paths.get(filename);
            try
            {
                Files.deleteIfExists(file);
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
            }
        }
    }
}
