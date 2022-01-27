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

package org.eclipse.jetty.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

public class JettyLoggingServiceProvider implements SLF4JServiceProvider
{
    /**
     * Declare the version of the SLF4J API this implementation is compiled against.
     */
    private static final String REQUESTED_API_VERSION = "2.0";

    private JettyLoggerFactory loggerFactory;
    private BasicMarkerFactory markerFactory;
    private MDCAdapter mdcAdapter;

    @Override
    public void initialize()
    {
        JettyLoggerConfiguration config = new JettyLoggerConfiguration().load(this.getClass().getClassLoader());
        loggerFactory = new JettyLoggerFactory(config);
        markerFactory = new BasicMarkerFactory();
        mdcAdapter = new NOPMDCAdapter(); // TODO: Provide Jetty Implementation?
    }

    public JettyLoggerFactory getJettyLoggerFactory()
    {
        return loggerFactory;
    }

    @Override
    public ILoggerFactory getLoggerFactory()
    {
        return getJettyLoggerFactory();
    }

    @Override
    public IMarkerFactory getMarkerFactory()
    {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter()
    {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion()
    {
        return REQUESTED_API_VERSION;
    }
}
