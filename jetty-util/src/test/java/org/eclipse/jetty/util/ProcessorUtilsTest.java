//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import org.junit.Assert;
import org.junit.Test;

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
        Assert.assertEquals(42, processors);

        // Make sure the original value is preserved.
        Assert.assertEquals(original, ProcessorUtils.availableProcessors());
    }

    @Test
    public void testSetter()
    {
        // Classloading will trigger the static initializer.
        int original = ProcessorUtils.availableProcessors();
        try
        {
            try
            {
                ProcessorUtils.setAvailableProcessors(0);
                Assert.fail();
            }
            catch (IllegalArgumentException expected)
            {
            }

            int processors = 42;
            ProcessorUtils.setAvailableProcessors(processors);
            Assert.assertEquals(processors, ProcessorUtils.availableProcessors());
        }
        finally
        {
            ProcessorUtils.setAvailableProcessors(original);
        }
    }
}
