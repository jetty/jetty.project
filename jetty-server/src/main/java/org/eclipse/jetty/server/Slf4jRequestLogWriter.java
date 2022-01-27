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

package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.LoggerFactory;

/**
 * Request log writer using a Slf4jLog Logger
 */
@ManagedObject("Slf4j RequestLog Writer")
public class Slf4jRequestLogWriter extends AbstractLifeCycle implements RequestLog.Writer
{
    private org.slf4j.Logger logger;
    private String loggerName;

    public Slf4jRequestLogWriter()
    {
        // Default logger name (can be set)
        this.loggerName = "org.eclipse.jetty.server.RequestLog";
    }

    public void setLoggerName(String loggerName)
    {
        this.loggerName = loggerName;
    }

    @ManagedAttribute("logger name")
    public String getLoggerName()
    {
        return loggerName;
    }

    protected boolean isEnabled()
    {
        return logger != null;
    }

    @Override
    public void write(String requestEntry) throws IOException
    {
        logger.info(requestEntry);
    }

    @Override
    protected void doStart() throws Exception
    {
        logger = LoggerFactory.getLogger(loggerName);
        super.doStart();
    }
}
