//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

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
            // dynamic configuration with slf4j didn't work (thread safe issue?)
            JettyLoggingServiceProvider provider = new JettyLoggingServiceProvider();
            provider.initialize();
            jettyLoggerFactory = provider.getJettyLoggerFactory();
        }
        loggerFactory = jettyLoggerFactory;
    }

    private final List<JettyLogger> squelched;

    public StacklessLogging(Class<?>... classesToSquelch)
    {
        this(Stream.of(classesToSquelch)
            .map(Class::getName)
            .toArray(String[]::new));
    }

    public StacklessLogging(Package... packagesToSquelch)
    {
        this(Stream.of(packagesToSquelch)
            .map(Package::getName)
            .toArray(String[]::new));
    }

    public StacklessLogging(String... loggerNames)
    {
        this(Stream.of(loggerNames)
            .map(loggerFactory::getJettyLogger)
            .toArray(Logger[]::new)
        );
    }

    public StacklessLogging(Logger... logs)
    {
        if (loggerFactory == null)
        {
            squelched = Collections.emptyList();
            return;
        }

        List<JettyLogger> stackless = new ArrayList<>();
        for (Logger log : logs)
        {
            if (log instanceof JettyLogger jettyLogger && !jettyLogger.isDebugEnabled())
                stackless.add(jettyLogger);
        }
        squelched = List.of(stackless.toArray(new JettyLogger[0]));

        JettyLogger.add(this);
    }

    @Override
    public void close()
    {
        JettyLogger.remove(this);
    }

    boolean isHiding(JettyLogger logger)
    {
        return squelched.contains(logger);
    }
}
