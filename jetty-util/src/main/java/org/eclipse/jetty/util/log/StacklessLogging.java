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

import java.util.HashSet;
import java.util.Set;

/**
 * A try-with-resources compatible layer for {@link StdErrLog#setHideStacks(boolean) hiding stacktraces} within the scope of the <code>try</code> block when
 * logging with {@link StdErrLog} implementation.
 * <p>
 * Use of other logging implementation cause no effect when using this class
 * <p>
 * Example:
 *
 * <pre>
 * try (StacklessLogging scope = new StacklessLogging(EventDriver.class,Noisy.class))
 * {
 *     doActionThatCausesStackTraces();
 * }
 * </pre>
 */
public class StacklessLogging implements AutoCloseable
{
    private final Set<StdErrLog> squelched = new HashSet<>();

    public StacklessLogging(Class<?>... classesToSquelch)
    {
        for (Class<?> clazz : classesToSquelch)
        {
            Logger log = Log.getLogger(clazz);
            // only operate on loggers that are of type StdErrLog
            if (log instanceof StdErrLog && !log.isDebugEnabled())
            {
                StdErrLog stdErrLog = ((StdErrLog)log);
                if (!stdErrLog.isHideStacks())
                {
                    stdErrLog.setHideStacks(true);
                    squelched.add(stdErrLog);
                }
            }
        }
    }

    public StacklessLogging(Logger... logs)
    {
        for (Logger log : logs)
        {
            // only operate on loggers that are of type StdErrLog
            if (log instanceof StdErrLog && !log.isDebugEnabled())
            {
                StdErrLog stdErrLog = ((StdErrLog)log);
                if (!stdErrLog.isHideStacks())
                {
                    stdErrLog.setHideStacks(true);
                    squelched.add(stdErrLog);
                }
            }
        }
    }

    @Override
    public void close()
    {
        for (StdErrLog log : squelched)
        {
            log.setHideStacks(false);
        }
    }
}
