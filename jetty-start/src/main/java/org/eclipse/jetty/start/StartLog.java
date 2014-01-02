//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized Place for logging.
 * <p>
 * Because startup cannot rely on Jetty's Logging, an alternative logging is established.
 * <p>
 * Optional behavior is to create a ${jetty.base}/logs/start.log with whatever output the startup process produces.
 */
public class StartLog
{
    private final static StartLog INSTANCE = new StartLog();

    public static void debug(String format, Object... args)
    {
        if (INSTANCE.debug)
        {
            System.out.printf(format + "%n",args);
        }
    }

    public static void debug(Throwable t)
    {
        if (INSTANCE.debug)
        {
            t.printStackTrace(System.out);
        }
    }

    public static StartLog getInstance()
    {
        return INSTANCE;
    }

    public static void info(String format, Object... args)
    {
        System.err.printf("INFO: " + format + "%n",args);
    }
    
    public static void warn(String format, Object... args)
    {
        System.err.printf("WARNING: " + format + "%n",args);
    }

    public static void warn(Throwable t)
    {
        t.printStackTrace(System.err);
    }
    
    public static boolean isDebugEnabled()
    {
        return INSTANCE.debug;
    }

    private boolean debug = false;

    public void initialize(BaseHome baseHome, StartArgs args) throws IOException
    {
        // Debug with boolean
        Pattern debugBoolPat = Pattern.compile("(-D)?debug=(.*)");
        // Log file name
        Pattern logFilePat = Pattern.compile("(-D)?start-log-file=(.*)");

        // TODO: support backward compatible --daemon argument ??

        Matcher matcher;
        for (String arg : args.getCommandLine())
        {
            if ("--debug".equals(arg))
            {
                debug = true;
                continue;
            }

            matcher = debugBoolPat.matcher(arg);
            if (matcher.matches())
            {
                debug = Boolean.parseBoolean(matcher.group(2));
                continue;
            }

            matcher = logFilePat.matcher(arg);
            if (matcher.matches())
            {
                String filename = matcher.group(2);
                File logfile = baseHome.getBaseFile(filename);
                initLogFile(logfile);
            }
        }
    }

    public void initLogFile(File logfile) throws IOException
    {
        if (logfile != null)
        {
            File logDir = logfile.getParentFile();
            if (!logDir.exists() || !logDir.canWrite())
            {
                String err = String.format("Cannot write %s to directory %s [directory doesn't exist or is read-only]",logfile.getName(),
                        logDir.getAbsolutePath());
                throw new UsageException(UsageException.ERR_LOGGING,new IOException(err));
            }

            File startLog = logfile;

            if (!startLog.exists() && !startLog.createNewFile())
            {
                // Output about error is lost in majority of cases.
                throw new UsageException(UsageException.ERR_LOGGING,new IOException("Unable to create: " + startLog.getAbsolutePath()));
            }

            if (!startLog.canWrite())
            {
                // Output about error is lost in majority of cases.
                throw new UsageException(UsageException.ERR_LOGGING,new IOException("Unable to write to: " + startLog.getAbsolutePath()));
            }
            PrintStream logger = new PrintStream(new FileOutputStream(startLog,false));
            System.setOut(logger);
            System.setErr(logger);
            System.out.println("Establishing " + logfile + " on " + new Date());
        }
    }

    public static void enableDebug()
    {
        getInstance().debug = true;
    }
}
