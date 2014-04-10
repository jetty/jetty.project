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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Date;

import org.eclipse.jetty.start.config.CommandLineConfigSource;

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

    public void initialize(BaseHome baseHome, CommandLineConfigSource cmdLineSource) throws IOException
    {
        String dbgProp = cmdLineSource.getProperty("debug");
        if (dbgProp != null)
        {
            debug = Boolean.parseBoolean(dbgProp);
        }

        String logFileName = cmdLineSource.getProperty("start-log-file");

        for (String arg : cmdLineSource.getArgs())
        {
            if ("--debug".equals(arg))
            {
                debug = true;
                continue;
            }

            if (arg.startsWith("--start-log-file"))
            {
                logFileName = Props.getValue(arg);
                continue;
            }
        }

        if (logFileName != null)
        {
            Path logfile = baseHome.getBasePath(logFileName);
            initLogFile(logfile);
        }
    }

    public void initLogFile(Path logfile) throws IOException
    {
        if (logfile != null)
        {
            try
            {
                Path logDir = logfile.getParent();
                FS.ensureDirectoryWritable(logDir);

                Path startLog = logfile;

                if (!FS.exists(startLog) && !FS.createNewFile(startLog))
                {
                    // Output about error is lost in majority of cases.
                    throw new UsageException(UsageException.ERR_LOGGING,new IOException("Unable to create: " + startLog.toAbsolutePath()));
                }

                if (!FS.canWrite(startLog))
                {
                    // Output about error is lost in majority of cases.
                    throw new UsageException(UsageException.ERR_LOGGING,new IOException("Unable to write to: " + startLog.toAbsolutePath()));
                }

                System.out.println("Logging to " + logfile);

                OutputStream out = Files.newOutputStream(startLog,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                PrintStream logger = new PrintStream(out);
                System.setOut(logger);
                System.setErr(logger);
                System.out.println("Establishing " + logfile + " on " + new Date());
            }
            catch (IOException e)
            {
                throw new UsageException(UsageException.ERR_LOGGING,e);
            }
        }
    }

    public static void enableDebug()
    {
        getInstance().debug = true;
    }
}
