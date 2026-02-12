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

package org.eclipse.jetty.io.content;

import java.util.List;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChunksContentSourceTest
{
    @Test
    public void testEmptyCollectionReadsEOF()
    {
        ChunksContentSource contentSource = new ChunksContentSource(List.of());
        Content.Chunk read = contentSource.read();
        assertFalse(read.getByteBuffer().hasRemaining());
        assertTrue(read.isLast());
    }
}
