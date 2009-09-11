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
package org.eclipse.jetty.webapp.logging;

import java.io.FileInputStream;
import java.io.IOException;
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
