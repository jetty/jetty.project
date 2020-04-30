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

    /**
     * Get a {@link JettyLogger} instance, creating if not yet existing.
     *
     * @param name the name of the logger
     * @return the JettyLogger instance
     */
    public JettyLogger getJettyLogger(String name)
    {
        if (name.equals(Logger.ROOT_LOGGER_NAME))
        {
            return getRootLogger();
        }

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

    protected void walkChildLoggers(String parentName, Consumer<JettyLogger> childConsumer)
    {
        String prefix = parentName;
        if (parentName.length() > 0 && !prefix.endsWith("."))
        {
            prefix += ".";
        }

        for (JettyLogger logger : loggerMap.values())
        {
            if (logger.getName().equals(parentName))
            {
                // skip self
                continue;
            }

            // is child, and is not itself
            if (logger.getName().startsWith(prefix))
            {
                childConsumer.accept(logger);
            }
        }
    }

    public JettyLogger getRootLogger()
    {
        return rootLogger;
    }

    private JettyLogger createLogger(String name)
    {
        // or is that handled by slf4j itself?
        JettyAppender appender = rootLogger.getAppender();
        int level = this.configuration.getLevel(name);
        boolean hideStacks = this.configuration.getHideStacks(name);
        return new JettyLogger(this, name, appender, level, hideStacks);
    }

    /**
     * Condenses a classname by stripping down the package name to just the first character of each package name
     * segment.Configured
     *
     * <pre>
     * Examples:
     * "org.eclipse.jetty.test.FooTest"           = "oejt.FooTest"
     * "org.eclipse.jetty.server.logging.LogTest" = "orjsl.LogTest"
     * </pre>
     *
     * @param classname the fully qualified class name
     * @return the condensed name
     */
    protected static String condensePackageString(String classname)
    {
        if (classname == null || classname.isEmpty())
        {
            return "";
        }

        int rawLen = classname.length();
        StringBuilder dense = new StringBuilder(rawLen);
        boolean foundStart = false;
        boolean hasPackage = false;
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < rawLen; i++)
        {
            char c = classname.charAt(i);
            if (!foundStart)
            {
                foundStart = Character.isJavaIdentifierStart(c);
                if (foundStart)
                {
                    if (startIdx >= 0)
                    {
                        dense.append(classname.charAt(startIdx));
                        hasPackage = true;
                    }
                    startIdx = i;
                }
            }

            if (foundStart)
            {
                if (!Character.isJavaIdentifierPart(c))
                {
                    foundStart = false;
                }
                else
                {
                    endIdx = i;
                }
            }
        }
        // append remaining from startIdx
        if ((startIdx >= 0) && (endIdx >= startIdx))
        {
            if (hasPackage)
            {
                dense.append('.');
            }
            dense.append(classname, startIdx, endIdx + 1);
        }

        return dense.toString();
    }

    public static <T> T walkParentLoggerNames(String startName, Function<String, T> nameFunction)
    {
        String nameSegment = startName;

        // Checking with FQCN first, then each package segment from longest to shortest.
        while ((nameSegment != null) && (nameSegment.length() > 0))
        {
            T ret = nameFunction.apply(nameSegment);
            if (ret != null)
                return ret;

            // Trim and try again.
            int idx = nameSegment.lastIndexOf('.');
            if (idx >= 0)
            {
                nameSegment = nameSegment.substring(0, idx);
            }
            else
            {
                nameSegment = null;
            }
        }

        return null;
    }

    @Override
    public String[] getLoggerNames()
    {
        return loggerMap.keySet().toArray(new String[0]);
    }

    @Override
    public int getLoggerCount()
    {
        return loggerMap.size();
    }

    @Override
    public String getLoggerLevel(String loggerName)
    {
        return walkParentLoggerNames(loggerName, (key) ->
        {
            JettyLogger logger = loggerMap.get(key);
            if (key != null)
            {
                return LevelUtils.levelToString(logger.getLevel());
            }
            return null;
        });
    }

    @Override
    public void setLoggerLevel(String loggerName, String levelName)
    {
        Integer levelInt = LevelUtils.getLevelInt(loggerName, levelName);
        if (levelInt != null)
        {
            JettyLogger jettyLogger = getJettyLogger(loggerName);
            jettyLogger.setLevel(levelInt);
        }
    }
}
