//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import com.acme.Managed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogMBeanTest
{

    private Managed managed;

    private LogMBean logMBean;

    private static final String MANAGED_CLASS = "Managed";

    @BeforeEach
    public void setUp()
    {
        managed = new Managed();
        logMBean = new LogMBean(managed);
    }

    @Test
    public void testKeySet()
    {
        // given
        assertThat("Managed is not registered with loggers", MANAGED_CLASS, not(is(in(logMBean.getLoggers()))));

        // when
        logMBean.setDebugEnabled(MANAGED_CLASS, true);

        // then
        assertThat("Managed must be registered with loggers", MANAGED_CLASS, is(in(logMBean.getLoggers())));
        assertTrue(logMBean.isDebugEnabled(MANAGED_CLASS), "This must return true as debug is enabled for this class");
    }
}
