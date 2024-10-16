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

package org.eclipse.jetty.compression;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CompressionTest extends AbstractCompressionTest
{
    @ParameterizedTest
    @MethodSource("compressions")
    public void testFileExtensionNames(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        List<String> list1 = compression.getFileExtensionNames();
        assertNotNull(list1);
        assertThat(list1, not(empty()));
        assertThrows(UnsupportedOperationException.class, () -> list1.add("bogus"), "should be an unmodifiable list");
        List<String> list2 = compression.getFileExtensionNames();
        assertNotNull(list2);
        assertThat(list2, not(empty()));
        assertThrows(UnsupportedOperationException.class, () -> list2.add("bogus"), "should be an unmodifiable list");
        assertSame(list1, list2, "Should always return the same list instance");
    }
}
