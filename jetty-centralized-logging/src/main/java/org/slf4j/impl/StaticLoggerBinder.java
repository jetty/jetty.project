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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
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
    private static final String SYSPROP_CONFIG_KEY = "org.eclipse.jetty.logging.config.file";

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
                Properties props = getConfigurationProperties();
                root = CentralLoggerConfig.load(props);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
        return root;
    }

    private Properties getConfigurationProperties()
    {
        String propertyFilename = System.getProperty(SYSPROP_CONFIG_KEY);
        if (propertyFilename != null)
        {
            System.out.printf("Found centralized-logging system property %s=%s%n",SYSPROP_CONFIG_KEY,propertyFilename);
            File file = new File(propertyFilename);
            if (file.exists() && file.isFile())
            {
                System.out.println("Loading centralized-logging configuration from file: " + file.getAbsolutePath());
                Properties props = new Properties();
                FileInputStream stream = null;
                try
                {
                    stream = new FileInputStream(file);
                    props.load(stream);
                    return props;
                }
                catch (IOException e)
                {
                    e.printStackTrace(System.err);
                }
                finally
                {
                    closeStream(stream);
                }
            }
            else
            {
                System.out.println("Cannot find file: " + file.getAbsolutePath());
            }
        }

        URL resourceUrl = this.getClass().getResource("centralized-logging.properties");
        if (resourceUrl != null)
        {
            System.out.println("Loading centralized-logging configuration from resource URL: " + resourceUrl.toExternalForm());
            InputStream stream = null;
            try
            {
                stream = resourceUrl.openStream();
                Properties props = new Properties();
                props.load(stream);
                return props;
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
            finally
            {
                closeStream(stream);
            }
        }

        System.out.println("Using default centralized-logging configuration");
        return getDefaultProperties();
    }

    private void closeStream(InputStream stream)
    {
        if (stream != null)
        {
            try
            {
                stream.close();
            }
            catch (Throwable ignore)
            {
                /* ignore */
            }
        }
    }

    private Properties getDefaultProperties()
    {
        Properties props = new Properties();
        props.setProperty("root.level","DEBUG");
        props.setProperty("root.appenders","console");
        props.setProperty("appender.console.class",ConsoleAppender.class.getName());
        return props;
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
