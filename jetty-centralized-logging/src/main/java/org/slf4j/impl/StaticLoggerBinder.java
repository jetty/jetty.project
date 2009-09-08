// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.slf4j.impl;

import java.io.IOException;
import java.util.Properties;

import org.eclipse.jetty.logging.impl.CentralLoggerConfig;
import org.eclipse.jetty.logging.impl.CentralLoggerFactory;
import org.eclipse.jetty.logging.impl.ConsoleAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * Standard entry point for Slf4J, used to configure the desired {@link ILoggerFactory}.
 */
public class StaticLoggerBinder implements LoggerFactoryBinder
{
    /**
     * Required by {@link org.slf4j.LoggerFactory}
     */
    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    /**
     * Required by {@link org.slf4j.LoggerFactory}
     */
    public static final StaticLoggerBinder getSingleton()
    {
        return SINGLETON;
    }

    /**
     * Required by {@link org.slf4j.LoggerFactory}, based on information from slf4j, this field should not be declared
     * final (it can cause problems with optimizations occurring within the java compiler)
     */
    public static/* final */String REQUESTED_API_VERSION = "1.5.6";

    private static final String loggerFactoryClassName = CentralLoggerFactory.class.getName();
    private CentralLoggerFactory loggerFactory;
    private CentralLoggerConfig root;

    public CentralLoggerConfig getRoot()
    {
        if(root == null) {
            try
            {
                Properties props = new Properties();
                props.setProperty("root.level","DEBUG");
                props.setProperty("root.appenders","console");
                props.setProperty("appender.console.class",ConsoleAppender.class.getName());
                root = CentralLoggerConfig.load(props);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
        return root;
    }

    public void setRoot(CentralLoggerConfig root)
    {
        this.root = root;
        if (loggerFactory == null)
        {
            loggerFactory = new CentralLoggerFactory(root);
        }
        else
        {
            loggerFactory.setRoot(root);
        }
    }

    public ILoggerFactory getLoggerFactory()
    {
        if (loggerFactory == null)
        {
            loggerFactory = new CentralLoggerFactory(getRoot());
        }

        return loggerFactory;
    }

    public String getLoggerFactoryClassStr()
    {
        return loggerFactoryClassName;
    }
}
