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
