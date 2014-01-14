//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
    private final Class<?> clazzes[];

    public StacklessLogging(Class<?>... classesToSquelch)
    {
        this.clazzes = classesToSquelch;
        hideStacks(true);
    }

    @Override
    public void close() throws Exception
    {
        hideStacks(false);
    }

    private void hideStacks(boolean hide)
    {
        for (Class<?> clazz : clazzes)
        {
            Logger log = Log.getLogger(clazz);
            if (log == null)
            {
                // not interested in classes without loggers
                continue;
            }
            if (log instanceof StdErrLog)
            {
                // only operate on loggers that are of type StdErrLog
                ((StdErrLog)log).setHideStacks(hide);
            }
        }
    }
}
