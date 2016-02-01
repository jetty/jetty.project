//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

/**
 * Redirect java.util.logging events to Jetty Log
 */
public class JettyLogHandler extends java.util.logging.Handler
{
    public static void config()
    {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL url = cl.getResource("logging.properties");
        if (url != null)
        {
            System.err.printf("Initializing java.util.logging from %s%n",url);
            try (InputStream in = url.openStream())
            {
                LogManager.getLogManager().readConfiguration(in);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        } 
        else 
        {
            System.err.printf("WARNING: java.util.logging failed to initialize: logging.properties not found%n");
        }

        System.setProperty("org.apache.commons.logging.Log","org.apache.commons.logging.impl.Jdk14Logger");
    }

    public JettyLogHandler()
    {
        if (Boolean.parseBoolean(Log.__props.getProperty("org.eclipse.jetty.util.log.DEBUG","false")))
        {
            setLevel(Level.FINEST);
        }

        if (Boolean.parseBoolean(Log.__props.getProperty("org.eclipse.jetty.util.log.IGNORED","false")))
        {
            setLevel(Level.ALL);
        }
        
        System.err.printf("%s Initialized at level [%s]%n",this.getClass().getName(),getLevel().getName());
    }

    private synchronized String formatMessage(LogRecord record)
    {
        String msg = getMessage(record);

        try
        {
            Object params[] = record.getParameters();
            if ((params == null) || (params.length == 0))
            {
                return msg;
            }

            if (Pattern.compile("\\{\\d+\\}").matcher(msg).find())
            {
                return MessageFormat.format(msg,params);
            }

            return msg;
        }
        catch (Exception ex)
        {
            return msg;
        }
    }

    private String getMessage(LogRecord record)
    {
        ResourceBundle bundle = record.getResourceBundle();
        if (bundle != null)
        {
            try
            {
                return bundle.getString(record.getMessage());
            }
            catch (java.util.MissingResourceException ex)
            {
            }
        }

        return record.getMessage();
    }

    @Override
    public void publish(LogRecord record)
    {
        org.eclipse.jetty.util.log.Logger JLOG = getJettyLogger(record.getLoggerName());

        int level = record.getLevel().intValue();
        if (level >= Level.OFF.intValue())
        {
            // nothing to log, skip it.
            return;
        }

        Throwable cause = record.getThrown();
        String msg = formatMessage(record);

        if (level >= Level.WARNING.intValue())
        {
            // log at warn
            if (cause != null)
            {
                JLOG.warn(msg,cause);
            }
            else
            {
                JLOG.warn(msg);
            }
            return;
        }

        if (level >= Level.INFO.intValue())
        {
            // log at info
            if (cause != null)
            {
                JLOG.info(msg,cause);
            }
            else
            {
                JLOG.info(msg);
            }
            return;
        }

        if (level >= Level.FINEST.intValue())
        {
            // log at debug
            if (cause != null)
            {
                JLOG.debug(msg,cause);
            }
            else
            {
                JLOG.debug(msg);
            }
            return;
        }

        if (level >= Level.ALL.intValue())
        {
            // only corresponds with ignore (in jetty speak)
            JLOG.ignore(cause);
            return;
        }
    }

    private Logger getJettyLogger(String loggerName)
    {
        return org.eclipse.jetty.util.log.Log.getLogger(loggerName);
    }

    @Override
    public void flush()
    {
        // ignore
    }

    @Override
    public void close() throws SecurityException
    {
        // ignore
    }
}
