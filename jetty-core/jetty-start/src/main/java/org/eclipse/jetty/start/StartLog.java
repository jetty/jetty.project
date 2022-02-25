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
    private static final PrintStream stdout = System.out;
    private static final PrintStream stderr = System.err;
    private static volatile PrintStream out = System.out;
    private static volatile PrintStream err = System.err;
    private static volatile PrintStream logStream = System.err;
    private static final StartLog INSTANCE = new StartLog();

    private static void outLog(String level, String format, Object... args)
    {
        out.printf(level + ": " + format + "%n", args);
    }

    public static void debug(String format, Object... args)
    {
        if (INSTANCE.debug)
        {
            outLog("DEBUG ", format, args);
        }
    }

    public static void debug(Throwable t)
    {
        if (INSTANCE.debug)
        {
            t.printStackTrace(out);
        }
    }

    public static void trace(String format, Object... args)
    {
        if (INSTANCE.trace)
        {
            outLog("TRACE ", format, args);
        }
    }

    public static StartLog getInstance()
    {
        return INSTANCE;
    }

    private static void log(String type, String msg)
    {
        logStream.printf("%-6s: %s%n", type, msg);
    }

    private static void log(String type, String format, Object... args)
    {
        log(type, String.format(format, args));
    }

    public static void info(String format, Object... args)
    {
        log("INFO", format, args);
    }

    public static void error(String format, Object... args)
    {
        log("ERROR", format, args);
    }

    public static void warn(String format, Object... args)
    {
        log("WARN", format, args);
    }

    public static void warn(Throwable t)
    {
        t.printStackTrace(logStream);
    }

    public static boolean isDebugEnabled()
    {
        return INSTANCE.debug;
    }

    private boolean trace = false;
    private boolean debug = false;

    public void initialize(BaseHome baseHome, CommandLineConfigSource cmdLineSource) throws IOException
    {
        String dbgProp = cmdLineSource.getProperty("debug");
        if (dbgProp != null)
        {
            debug = Boolean.parseBoolean(dbgProp);
        }

        String logFileName = cmdLineSource.getProperty("start-log-file");

        for (RawArgs.Entry arg : cmdLineSource.getArgs())
        {
            if ("--debug".equals(arg.getLine()))
            {
                debug = true;
                continue;
            }

            if (arg.startsWith("--start-log-file"))
            {
                logFileName = Props.getValue(arg.getLine());
                continue;
            }
        }

        if (logFileName != null)
        {
            Path logfile = baseHome.getPath(logFileName);
            logfile = logfile.toAbsolutePath();
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
                    throw new UsageException(UsageException.ERR_LOGGING, new IOException("Unable to create: " + startLog.toAbsolutePath()));
                }

                if (!FS.canWrite(startLog))
                {
                    // Output about error is lost in majority of cases.
                    throw new UsageException(UsageException.ERR_LOGGING, new IOException("Unable to write to: " + startLog.toAbsolutePath()));
                }

                err.println("StartLog to " + logfile);
                OutputStream fileout = Files.newOutputStream(startLog, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                PrintStream logger = new PrintStream(fileout, true);
                out = logger;
                err = logger;
                setStream(logger);
                System.setErr(logger);
                System.setOut(logger);
                err.println("StartLog Establishing " + logfile + " on " + new Date());
            }
            catch (IOException e)
            {
                throw new UsageException(UsageException.ERR_LOGGING, e);
            }
        }
    }

    public static void enableDebug()
    {
        getInstance().debug = true;
    }

    public static void endStartLog()
    {
        if (stderr != err && getInstance().debug)
        {
            err.println("StartLog ended");
            stderr.println("StartLog ended");
        }
        setStream(stderr);
        System.setErr(stderr);
        System.setOut(stdout);
    }

    public static PrintStream getStream()
    {
        return logStream;
    }

    public static PrintStream setStream(PrintStream stream)
    {
        PrintStream ret = logStream;
        logStream = stream;
        return ret;
    }
}
