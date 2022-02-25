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

package org.eclipse.jetty.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ProcessorUtilsTest
{
    @Test
    public void testSystemProperty()
    {
        // Classloading will trigger the static initializer.
        int original = ProcessorUtils.availableProcessors();

        // Verify that the static initializer logic is correct.
        System.setProperty(ProcessorUtils.AVAILABLE_PROCESSORS, "42");
        int processors = ProcessorUtils.init();
        assertEquals(42, processors);

        // Make sure the original value is preserved.
        assertEquals(original, ProcessorUtils.availableProcessors());
    }

    @Test
    public void testSetter()
    {
        // Classloading will trigger the static initializer.
        int original = ProcessorUtils.availableProcessors();
        try
        {
            assertThrows(IllegalArgumentException.class, () -> ProcessorUtils.setAvailableProcessors(0));

            int processors = 42;
            ProcessorUtils.setAvailableProcessors(processors);
            assertEquals(processors, ProcessorUtils.availableProcessors());
        }
        finally
        {
            ProcessorUtils.setAvailableProcessors(original);
        }
    }
}
