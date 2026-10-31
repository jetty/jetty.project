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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

public class ZstandardCleanerTest
{
    @Test
    public void testInstancesShareOneCleaner()
    {
        // Every Cleaner starts a thread, so a Cleaner per instance leaves a thread behind
        // for every HttpClient that is started.
        ZstandardCompression first = new ZstandardCompression();
        ZstandardCompression second = new ZstandardCompression();

        assertSame(first.getCleaner(), second.getCleaner());
    }
}
