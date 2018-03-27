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
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * we cannot really add env var in a unit test... so only test we get default value
 */
public class ProcessorUtilsTest
{
    @BeforeClass
    public static void beforeClass()
    {
        System.setProperty("JETTY_AVAILABLE_PROCESSORS","42");
    }
    
    @Test
    public void getPropertyValue()
    {
        Assert.assertEquals(42, ProcessorUtils.availableProcessors());
    }
}
