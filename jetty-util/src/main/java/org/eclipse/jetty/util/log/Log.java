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

package org.eclipse.jetty.util.log;

import org.slf4j.LoggerFactory;

/**
 * Legacy bridge API to Slf4j
 *
 * @deprecated
 */
@Deprecated
public class Log
{
    @Deprecated
    public static final String EXCEPTION = "EXCEPTION";

    @Deprecated
    public static Logger getLogger(Class<?> clazz)
    {
        return new Logger(LoggerFactory.getLogger(clazz));
    }

    @Deprecated
    public static Logger getLogger(String name)
    {
        return new Logger(LoggerFactory.getLogger(name));
    }

    @Deprecated
    public static Logger getRootLogger()
    {
        return new Logger(LoggerFactory.getLogger(""));
    }
}
