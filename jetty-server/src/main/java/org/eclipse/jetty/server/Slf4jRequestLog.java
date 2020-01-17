//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

/**
 * Implementation of NCSARequestLog where output is sent as a SLF4J INFO Log message on the named logger "org.eclipse.jetty.server.RequestLog"
 *
 * @deprecated use {@link CustomRequestLog} given format string {@link CustomRequestLog#EXTENDED_NCSA_FORMAT} with an {@link Slf4jRequestLogWriter}
 */
@Deprecated
@ManagedObject("NCSA standard format request log to slf4j bridge")
public class Slf4jRequestLog extends AbstractNCSARequestLog
{
    private final Slf4jRequestLogWriter _requestLogWriter;

    public Slf4jRequestLog()
    {
        this(new Slf4jRequestLogWriter());
    }

    public Slf4jRequestLog(Slf4jRequestLogWriter writer)
    {
        super(writer);
        _requestLogWriter = writer;
    }

    public void setLoggerName(String loggerName)
    {
        _requestLogWriter.setLoggerName(loggerName);
    }

    public String getLoggerName()
    {
        return _requestLogWriter.getLoggerName();
    }

    @Override
    protected boolean isEnabled()
    {
        return _requestLogWriter.isEnabled();
    }

    @Override
    public void write(String requestEntry) throws IOException
    {
        _requestLogWriter.write(requestEntry);
    }
}
