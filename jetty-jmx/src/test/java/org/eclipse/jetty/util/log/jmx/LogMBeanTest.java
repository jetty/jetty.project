//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.log.jmx;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import org.junit.Before;
import org.junit.Test;
import com.acme.Managed;

public class LogMBeanTest
{

    private Managed managed;

    private LogMBean logMBean;

    private static final String MANAGED_CLASS = "Managed";

    @Before
    public void setUp()
    {
        managed = new Managed();
        logMBean = new LogMBean(managed);
    }

    @Test
    public void testKeySet()
    {
        // given
        assertFalse("Managed is not registered with loggers",logMBean.getLoggers().contains(MANAGED_CLASS));

        // when
        logMBean.setDebugEnabled(MANAGED_CLASS,true);

        // then
        assertTrue("Managed must be registered with loggers",logMBean.getLoggers().contains(MANAGED_CLASS));
        assertTrue("This must return true as debug is enabled for this class",logMBean.isDebugEnabled(MANAGED_CLASS));
    }
}
