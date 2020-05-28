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

import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

public class JettyLoggerFactory implements ILoggerFactory, JettyLoggerFactoryMBean
{
    private final JettyLoggerConfiguration configuration;
    private final JettyLogger rootLogger;
    private final ConcurrentMap<String, JettyLogger> loggerMap;

    public JettyLoggerFactory(JettyLoggerConfiguration config)
    {
        configuration = Objects.requireNonNull(config, "JettyLoggerConfiguration");

        loggerMap = new ConcurrentHashMap<>();

        StdErrAppender appender = new StdErrAppender(configuration);

        rootLogger = new JettyLogger(this, Logger.ROOT_LOGGER_NAME, appender);
        loggerMap.put(Logger.ROOT_LOGGER_NAME, rootLogger);
        rootLogger.setLevel(configuration.getLevel(Logger.ROOT_LOGGER_NAME));
    }

    @SuppressWarnings("unused")
    public String jmxContext()
    {
        // Used to build the ObjectName.
        return configuration.getString("org.eclipse.jetty.logging.jmx.context", null);
    }

    /**
     * Get a {@link JettyLogger} instance, creating if not yet existing.
     *
     * @param name the name of the logger
     * @return the JettyLogger instance
     */
    public JettyLogger getJettyLogger(String name)
    {
        if (name.equals(Logger.ROOT_LOGGER_NAME))
            return getRootLogger();
        return loggerMap.computeIfAbsent(name, this::createLogger);
    }

    /**
     * Main interface for {@link ILoggerFactory}
     *
     * @param name the name of the logger
     * @return the Slf4j Logger
     */
    @Override
    public Logger getLogger(String name)
    {
        return getJettyLogger(name);
    }

    void walkChildrenLoggers(String parentName, Consumer<JettyLogger> childConsumer)
    {
        String prefix = parentName;
        if (parentName.length() > 0 && !prefix.endsWith("."))
            prefix += ".";

        for (JettyLogger logger : loggerMap.values())
        {
            // Skip self.
            if (logger.getName().equals(parentName))
                continue;

            // It is a child, and is not itself.
            if (logger.getName().startsWith(prefix))
                childConsumer.accept(logger);
        }
    }

    JettyLogger getRootLogger()
    {
        return rootLogger;
    }

    private JettyLogger createLogger(String name)
    {
        JettyAppender appender = rootLogger.getAppender();
        JettyLevel level = this.configuration.getLevel(name);
        boolean hideStacks = this.configuration.getHideStacks(name);
        return new JettyLogger(this, name, appender, level, hideStacks);
    }

    static <T> T walkParentLoggerNames(String startName, Function<String, T> nameFunction)
    {
        if (startName == null)
            return null;

        // Checking with FQCN first, then each package segment from longest to shortest.
        String nameSegment = startName;
        while (nameSegment.length() > 0)
        {
            T ret = nameFunction.apply(nameSegment);
            if (ret != null)
                return ret;

            // Trim and try again.
            int idx = nameSegment.lastIndexOf('.');
            if (idx >= 0)
                nameSegment = nameSegment.substring(0, idx);
            else
                break;
        }

        return nameFunction.apply(Logger.ROOT_LOGGER_NAME);
    }

    @Override
    public String[] getLoggerNames()
    {
        TreeSet<String> names = new TreeSet<>(loggerMap.keySet());
        return names.toArray(new String[0]);
    }

    @Override
    public int getLoggerCount()
    {
        return loggerMap.size();
    }

    @Override
    public String getLoggerLevel(String loggerName)
    {
        return walkParentLoggerNames(loggerName, key ->
        {
            JettyLogger logger = loggerMap.get(key);
            if (logger != null)
                return logger.getLevel().name();
            return null;
        });
    }

    @Override
    public boolean setLoggerLevel(String loggerName, String levelName)
    {
        JettyLevel level = JettyLoggerConfiguration.toJettyLevel(loggerName, levelName);
        if (level == null)
        {
            return false;
        }
        JettyLogger jettyLogger = getJettyLogger(loggerName);
        jettyLogger.setLevel(level);
        return true;
    }
}
