//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.log.jmx;

import com.acme.Managed;
import org.eclipse.jetty.logging.jmx.LogMBean;
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
