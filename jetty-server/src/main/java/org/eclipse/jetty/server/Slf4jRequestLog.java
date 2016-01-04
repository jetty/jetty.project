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

package org.eclipse.jetty.server;

import java.io.IOException;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Slf4jLog;

/**
 * Implementation of NCSARequestLog where output is sent as a SLF4J INFO Log message on the named logger "org.eclipse.jetty.server.RequestLog"
 */
@ManagedObject("NCSA standard format request log to slf4j bridge")
public class Slf4jRequestLog extends AbstractNCSARequestLog
{
    private Slf4jLog logger;
    private String loggerName;

    public Slf4jRequestLog()
    {
        // Default logger name (can be set)
        this.loggerName = "org.eclipse.jetty.server.RequestLog";
    }

    public void setLoggerName(String loggerName)
    {
        this.loggerName = loggerName;
    }

    public String getLoggerName()
    {
        return loggerName;
    }

    @Override
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
    protected synchronized void doStart() throws Exception
    {
        logger = new Slf4jLog(loggerName);
        super.doStart();
    }
}
