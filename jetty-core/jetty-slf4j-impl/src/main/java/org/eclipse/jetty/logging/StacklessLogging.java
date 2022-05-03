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

import java.util.HashSet;
import java.util.Set;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A try-with-resources compatible layer for {@link JettyLogger#setHideStacks(boolean) hiding stacktraces} within the scope of the <code>try</code> block when
 * logging with {@link JettyLogger} implementation.
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
    private static final Logger LOG = LoggerFactory.getLogger(StacklessLogging.class);
    private static final JettyLoggerFactory loggerFactory;

    static
    {
        JettyLoggerFactory jettyLoggerFactory = null;
        ILoggerFactory activeLoggerFactory = LoggerFactory.getILoggerFactory();
        if (activeLoggerFactory instanceof JettyLoggerFactory)
        {
            jettyLoggerFactory = (JettyLoggerFactory)activeLoggerFactory;
        }
        else
        {
            LOG.warn("Unable to squelch stacktraces ({} is not a {})",
                activeLoggerFactory.getClass().getName(),
                JettyLoggerFactory.class.getName());
        }
        loggerFactory = jettyLoggerFactory;
    }

    private final Set<JettyLogger> squelched = new HashSet<>();

    public StacklessLogging(Class<?>... classesToSquelch)
    {
        for (Class<?> clazz : classesToSquelch)
        {
            JettyLogger jettyLogger = loggerFactory.getJettyLogger(clazz.getName());
            if (!jettyLogger.isDebugEnabled())
            {
                if (!jettyLogger.isHideStacks())
                {
                    jettyLogger.setHideStacks(true);
                    squelched.add(jettyLogger);
                }
            }
        }
    }

    public StacklessLogging(Package... packagesToSquelch)
    {
        for (Package pkg : packagesToSquelch)
        {
            JettyLogger jettyLogger = loggerFactory.getJettyLogger(pkg.getName());
            if (!jettyLogger.isDebugEnabled())
            {
                if (!jettyLogger.isHideStacks())
                {
                    jettyLogger.setHideStacks(true);
                    squelched.add(jettyLogger);
                }
            }
        }
    }

    public StacklessLogging(Logger... logs)
    {
        for (Logger log : logs)
        {
            if (log instanceof JettyLogger && !log.isDebugEnabled())
            {
                JettyLogger jettyLogger = ((JettyLogger)log);
                if (!jettyLogger.isHideStacks())
                {
                    jettyLogger.setHideStacks(true);
                    squelched.add(jettyLogger);
                }
            }
        }
    }

    @Override
    public void close()
    {
        for (JettyLogger log : squelched)
        {
            log.setHideStacks(false);
        }
    }
}
