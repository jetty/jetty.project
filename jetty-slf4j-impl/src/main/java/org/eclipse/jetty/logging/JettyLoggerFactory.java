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

import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanConstructorInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ReflectionException;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

public class JettyLoggerFactory implements ILoggerFactory, DynamicMBean
{
    private final JettyLoggerConfiguration configuration;
    private final JettyLogger rootLogger;
    private final ConcurrentMap<String, JettyLogger> loggerMap;
    private MBeanInfo mBeanInfo;

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

    public String[] getLoggerNames()
    {
        TreeSet<String> names = new TreeSet<>(loggerMap.keySet());
        return names.toArray(new String[0]);
    }

    public int getLoggerCount()
    {
        return loggerMap.size();
    }

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

    @Override
    public Object getAttribute(String name) throws AttributeNotFoundException
    {
        Objects.requireNonNull(name, "Attribute Name");

        switch (name)
        {
            case "LoggerNames":
                return getLoggerNames();
            case "LoggerCount":
                return getLoggerCount();
            default:
                throw new AttributeNotFoundException("Cannot find " + name + " attribute in " + this.getClass().getName());
        }
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException
    {
        Objects.requireNonNull(attribute, "attribute");
        String name = attribute.getName();
        // No attributes are writable
        throw new AttributeNotFoundException("Cannot set attribute " + name + " because it is read-only");
    }

    @Override
    public AttributeList getAttributes(String[] attributeNames)
    {
        Objects.requireNonNull(attributeNames, "attributeNames[]");

        AttributeList ret = new AttributeList();
        if (attributeNames.length == 0)
            return ret;

        for (String name : attributeNames)
        {
            try
            {
                Object value = getAttribute(name);
                ret.add(new Attribute(name, value));
            }
            catch (Exception e)
            {
                // nothing much we can do, this method has no throwables, and we cannot use logging here.
                e.printStackTrace();
            }
        }
        return ret;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes)
    {
        Objects.requireNonNull(attributes, "attributes");

        AttributeList ret = new AttributeList();

        if (attributes.isEmpty())
            return ret;

        for (Attribute attr : attributes.asList())
        {
            try
            {
                setAttribute(attr);
                String name = attr.getName();
                Object value = getAttribute(name);
                ret.add(new Attribute(name, value));
            }
            catch (Exception e)
            {
                // nothing much we can do, this method has no throwables, and we cannot use logging here.
                e.printStackTrace();
            }
        }
        return ret;
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException
    {
        Objects.requireNonNull(actionName, "Action Name");

        switch (actionName)
        {
            case "setLoggerLevel":
            {
                String loggerName = (String)params[0];
                String level = (String)params[1];
                return setLoggerLevel(loggerName, level);
            }
            case "getLoggerLevel":
            {
                String loggerName = (String)params[0];
                return getLoggerLevel(loggerName);
            }
            default:
                throw new ReflectionException(
                    new NoSuchMethodException(actionName),
                    "Cannot find the operation " + actionName + " in " + this.getClass().getName());
        }
    }

    @Override
    public MBeanInfo getMBeanInfo()
    {
        if (mBeanInfo == null)
        {
            MBeanAttributeInfo[] attrs = new MBeanAttributeInfo[2];

            attrs[0] = new MBeanAttributeInfo(
                "LoggerCount",
                "java.lang.Integer",
                "Count of Registered Loggers by Name.",
                true,
                false,
                false);
            attrs[1] = new MBeanAttributeInfo(
                "LoggerNames",
                "java.lang.String[]",
                "List of Registered Loggers by Name.",
                true,
                false,
                false);

            MBeanOperationInfo[] operations = new MBeanOperationInfo[]{
                new MBeanOperationInfo(
                    "setLoggerLevel",
                    "Set the logging level at the named logger",
                    new MBeanParameterInfo[]{
                        new MBeanParameterInfo("loggerName", "java.lang.String", "The name of the logger"),
                        new MBeanParameterInfo("level", "java.lang.String", "The name of the level [DEBUG, INFO, WARN, ERROR]")
                    },
                    "boolean",
                    MBeanOperationInfo.ACTION
                ),
                new MBeanOperationInfo(
                    "getLoggerLevel",
                    "Get the logging level at the named logger",
                    new MBeanParameterInfo[]{
                        new MBeanParameterInfo("loggerName", "java.lang.String", "The name of the logger")
                    },
                    "java.lang.String",
                    MBeanOperationInfo.INFO
                )
            };

            mBeanInfo = new MBeanInfo(this.getClass().getName(),
                "Jetty Slf4J Logger Factory",
                attrs,
                new MBeanConstructorInfo[0],
                operations,
                new MBeanNotificationInfo[0]);
        }
        return mBeanInfo;
    }
}
