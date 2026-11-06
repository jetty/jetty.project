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

package org.eclipse.jetty.compression.zstandard;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ZstandardCompressionTest
{
    @Test
    public void testInstancesDoNotEachStartACleanerThread() throws Exception
    {
        Set<Thread> before = cleanerThreads();

        List<ZstandardCompression> compressions = new ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            ZstandardCompression zstd = new ZstandardCompression();
            zstd.setByteBufferPool(new ArrayByteBufferPool());
            zstd.start();
            compressions.add(zstd);
        }

        try
        {
            // Set difference rather than count difference: Cleaner threads
            // of unrelated, unreachable objects may exit at any time.
            Set<Thread> added = cleanerThreads();
            added.removeAll(before);
            // At most one, the shared Cleaner thread if it was not already started.
            assertThat(added.size(), lessThanOrEqualTo(1));
            assertThat(compressions, hasSize(20));
        }
        finally
        {
            compressions.forEach(c -> LifeCycle.stop(c));
        }
    }

    private static Set<Thread> cleanerThreads()
    {
        // Threads started by Cleaner.create() are named "Cleaner-N" by the JDK.
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .filter(t -> t.getName().startsWith("Cleaner-"))
            .collect(Collectors.toSet());
    }
}
