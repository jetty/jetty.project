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

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

public class JettyLoggerFactory implements ILoggerFactory
{
    protected static JettyLoggerFactory getLoggerFactory()
    {
        if (instance == null)
        {
            instance = new JettyLoggerFactory();
        }

        return instance;
    }

    protected static void setInstance(JettyLoggerFactory loggerFactory)
    {
        if (loggerFactory != null && instance != null)
        {
            System.err.printf("Replacing main Instance %s@%x with %s@%x",
                instance.getClass().getName(),
                instance.hashCode(),
                loggerFactory.getClass().getName(),
                loggerFactory.hashCode());
        }
        instance = loggerFactory;
    }

    private static JettyLoggerFactory instance;

    private static final String ROOT_LOGGER_NAME = "";
    private boolean initialized = false;
    private JettyLoggerConfiguration configuration;
    private JettyLogger rootLogger;
    private ConcurrentMap<String, JettyLogger> loggerMap;

    private JettyLoggerFactory()
    {
    }

    public JettyLoggerFactory initialize(JettyLoggerConfiguration config)
    {
        configuration = Objects.requireNonNull(config, "JettyLoggerConfiguration");

        loggerMap = new ConcurrentHashMap<>();

        rootLogger = new JettyLogger(ROOT_LOGGER_NAME);
        loggerMap.put(ROOT_LOGGER_NAME, rootLogger);

        rootLogger.setLevel(configuration.getLevel(ROOT_LOGGER_NAME));
        rootLogger.setAppender(new StdErrAppender(configuration));

        initialized = true;
        return this;
    }

    private void assertInitialized()
    {
        if (!initialized)
        {
            throw new IllegalStateException(this.getClass().getSimpleName() + " is not initialized yet");
        }
    }

    /**
     * Get a {@link JettyLogger} instance, creating if not yet existing.
     *
     * @param name the name of the logger
     * @return the JettyLogger instance
     */
    public JettyLogger getJettyLogger(String name)
    {
        assertInitialized();

        if (name.equals(ROOT_LOGGER_NAME))
        {
            return getRootLogger();
        }

        JettyLogger jettyLogger = loggerMap.get(name);
        if (jettyLogger == null)
        {
            jettyLogger = createLogger(name);
            loggerMap.putIfAbsent(name, jettyLogger);
        }
        return jettyLogger;
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

    public JettyLogger getConfiguredJettyLogger(Class<?> clazz)
    {
        return getConfiguredJettyLogger(clazz.getName());
    }

    public JettyLogger getConfiguredJettyLogger(String name)
    {
        assertInitialized();

        if (name.equals(ROOT_LOGGER_NAME))
        {
            return getRootLogger();
        }
        return loggerMap.get(name);
    }

    public JettyLogger getRootLogger()
    {
        assertInitialized();

        return rootLogger;
    }

    private JettyLogger createLogger(String name)
    {
        // TODO: if SOURCE property is configured, return a org.slf4j.spi.LocationAwareLogger wrapper?
        // or is that handled by slf4j itself?
        JettyLogger jettyLogger = new JettyLogger(name);
        jettyLogger.setLevel(this.configuration.getLevel(name));
        jettyLogger.setHideStacks(this.configuration.getHideStacks(name));
        jettyLogger.setAppender(rootLogger.getAppender());
        return jettyLogger;
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
}
