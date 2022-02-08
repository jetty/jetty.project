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

package org.eclipse.jetty.util.log;

import org.slf4j.LoggerFactory;

/**
 * Legacy bridge API to Slf4j
 *
 * @deprecated
 */
public class Log
{
    @Deprecated
    public static final String EXCEPTION = "EXCEPTION";

    @Deprecated
    public static org.eclipse.jetty.util.log.Logger getLogger(Class<?> clazz)
    {
        return new Slf4jLogger(LoggerFactory.getLogger(clazz));
    }

    @Deprecated
    public static org.eclipse.jetty.util.log.Logger getLogger(String name)
    {
        return new Slf4jLogger(LoggerFactory.getLogger(name));
    }

    @Deprecated
    public static org.eclipse.jetty.util.log.Logger getRootLogger()
    {
        return new Slf4jLogger(LoggerFactory.getLogger(""));
    }

    @Deprecated
    public static org.eclipse.jetty.util.log.Logger getLog()
    {
        return getRootLogger();
    }

    @Deprecated
    public static void setLog(org.eclipse.jetty.util.log.Logger log)
    {
        // does nothing
    }
}
