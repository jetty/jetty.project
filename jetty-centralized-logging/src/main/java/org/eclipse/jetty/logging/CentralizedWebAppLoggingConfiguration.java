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
package org.eclipse.jetty.logging;

import java.io.FileInputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.eclipse.jetty.logging.impl.CentralLoggerConfig;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Slf4jLog;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebAppContext {@link Configuration} for Centralized Logging.
 */
public class CentralizedWebAppLoggingConfiguration implements Configuration
{
    private static boolean loggerConfigured = false;

    public static CentralLoggerConfig getLoggerRoot()
    {
        loggerConfigured = true;
        return org.slf4j.impl.StaticLoggerBinder.getSingleton().getRoot();
    }

    public static boolean isLoggerConfigured()
    {
        return loggerConfigured;
    }

    public static void setConfiguredLoggerRoot(CentralLoggerConfig root)
    {
        loggerConfigured = true;
        org.slf4j.impl.StaticLoggerBinder.getSingleton().setRoot(root);
        try
        {
            // Reset jetty logger.
            Slf4jLog jettyLogger = new Slf4jLog();
            Log.setLog(jettyLogger);
        }
        catch (Exception ignore)
        {
            // ignore
        }
    }

    public static void setLoggerConfigurationFilename(String filename) throws IOException
    {
        FileInputStream stream = null;
        try
        {
            stream = new FileInputStream(filename);
            CentralLoggerConfig root = CentralLoggerConfig.load(stream);
            setConfiguredLoggerRoot(root);
            loggerConfigured = true;
        }
        finally
        {
            IO.close(stream);
        }
    }

    public void configure(WebAppContext context) throws Exception
    {
        context.addSystemClass("org.apache.log4j.");
        context.addSystemClass("org.slf4j.");
        context.addSystemClass("org.apache.commons.logging.");

        initializeJavaUtilLogRouting();
    }

    private static boolean javaUtilLoggingInitialized = false;

    private static void initializeJavaUtilLogRouting()
    {
        if (javaUtilLoggingInitialized)
        {
            return;
        }

        // Get root logger.
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");

        // Remove all existing Handlers.
        for (java.util.logging.Handler handler : rootLogger.getHandlers())
        {
            rootLogger.removeHandler(handler);
        }

        // Add our only handler.
        rootLogger.addHandler(new java.util.logging.Handler()
        {
            @Override
            public void close() throws SecurityException
            {
                /* do nothing */
            }

            @Override
            public void flush()
            {
                /* do nothing */
            }

            @Override
            public void publish(java.util.logging.LogRecord record)
            {
                org.slf4j.Logger slf4jLogger = getCentralLogger(record);

                int javaLevel = record.getLevel().intValue();

                // TRACE?
                if (javaLevel <= java.util.logging.Level.FINEST.intValue())
                {
                    if (slf4jLogger.isTraceEnabled())
                    {
                        String message = formatMessage(record);
                        slf4jLogger.trace(message,record.getThrown());
                    }
                    return;
                }

                // DEBUG?
                if (javaLevel <= java.util.logging.Level.FINE.intValue())
                {
                    if (slf4jLogger.isDebugEnabled())
                    {
                        String message = formatMessage(record);
                        slf4jLogger.debug(message,record.getThrown());
                    }
                    return;
                }

                // INFO?
                if (javaLevel <= java.util.logging.Level.INFO.intValue())
                {
                    if (slf4jLogger.isInfoEnabled())
                    {
                        String message = formatMessage(record);
                        slf4jLogger.info(message,record.getThrown());
                    }
                    return;
                }

                // WARN?
                if (javaLevel <= java.util.logging.Level.WARNING.intValue())
                {
                    if (slf4jLogger.isWarnEnabled())
                    {
                        String message = formatMessage(record);
                        slf4jLogger.warn(message,record.getThrown());
                    }
                    return;
                }

                // All others are ERROR
                if (slf4jLogger.isErrorEnabled())
                {
                    String message = formatMessage(record);
                    slf4jLogger.error(message,record.getThrown());
                }
            }

            private String formatMessage(java.util.logging.LogRecord record)
            {
                // Cand be raw freeform message, or resource bundle key, or null
                String raw = record.getMessage();

                // Null? (easy)
                if (raw == null)
                {
                    return null;
                }

                // Try using resource bundle
                ResourceBundle bundle = record.getResourceBundle();
                if (bundle != null)
                {
                    try
                    {
                        raw = bundle.getString(raw);
                    }
                    catch (MissingResourceException e)
                    {
                        /* ignore */
                    }
                }

                // Now blend in any (optional) parameters
                // Normally we would do this in the slf4j interface,
                // but that lacks the ability to do a formatted message
                // and a throwable at the same time.
                if (record.getParameters() != null)
                {
                    raw = MessageFormat.format(raw,record.getParameters());
                }

                return raw;
            }

            private org.slf4j.Logger getCentralLogger(java.util.logging.LogRecord record)
            {
                String name = record.getLoggerName();
                if (name == null)
                {
                    name = "anonymous";
                }
                return org.slf4j.LoggerFactory.getLogger(name);
            }
        });

        // Tweak levels.
        rootLogger.setLevel(java.util.logging.Level.ALL);

        javaUtilLoggingInitialized = true;
    }

    public void deconfigure(WebAppContext context) throws Exception
    {
        /* do nothing */
    }

    public void postConfigure(WebAppContext context) throws Exception
    {
        /* do nothing */
    }

    public void preConfigure(WebAppContext context) throws Exception
    {
        /* do nothing */
    }
}
