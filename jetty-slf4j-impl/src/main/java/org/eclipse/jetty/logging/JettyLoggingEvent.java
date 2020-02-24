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

package org.eclipse.jetty.logging;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.event.LoggingEvent;

public class JettyLoggingEvent implements LoggingEvent
{
    private final Level level;
    private final String name;
    private final String message;
    private final Object[] argumentArray;
    private final Throwable throwable;
    private final String threadName;
    private final long timestamp;
    private final String condensedName;
    private final boolean hideStacks;

    public JettyLoggingEvent(JettyLogger jettyLogger, Level level, String threadName, long timestamp, String msg, Throwable throwable, Object... args)
    {
        this.name = jettyLogger.getName();
        this.condensedName = jettyLogger.getCondensedName();
        this.hideStacks = jettyLogger.isHideStacks();
        this.level = level;
        this.message = msg;
        this.argumentArray = args;
        this.throwable = throwable;
        this.threadName = threadName;
        this.timestamp = timestamp;
    }

    public String getCondensedName()
    {
        return condensedName;
    }

    public boolean isHideStacks()
    {
        return hideStacks;
    }

    @Override
    public Level getLevel()
    {
        return this.level;
    }

    @Override
    public Marker getMarker()
    {
        return null;
    }

    @Override
    public String getLoggerName()
    {
        return this.name;
    }

    @Override
    public String getMessage()
    {
        return this.message;
    }

    @Override
    public String getThreadName()
    {
        return this.threadName;
    }

    @Override
    public Object[] getArgumentArray()
    {
        return this.argumentArray;
    }

    @Override
    public long getTimeStamp()
    {
        return this.timestamp;
    }

    @Override
    public Throwable getThrowable()
    {
        return this.throwable;
    }
}
