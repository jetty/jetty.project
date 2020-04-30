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

import java.util.Locale;

import org.slf4j.event.Level;

public class LevelUtils
{
    public static Integer getLevelInt(String loggerName, String levelStr)
    {
        if (levelStr == null)
        {
            return null;
        }

        String levelName = levelStr.trim().toUpperCase(Locale.ENGLISH);
        switch (levelName)
        {
            case "ALL":
                return JettyLogger.ALL;
            case "TRACE":
                return Level.TRACE.toInt();
            case "DEBUG":
                return Level.DEBUG.toInt();
            case "INFO":
                return Level.INFO.toInt();
            case "WARN":
                return Level.WARN.toInt();
            case "ERROR":
                return Level.ERROR.toInt();
            case "OFF":
                return JettyLogger.OFF;
            default:
                System.err.println("Unknown JettyLogger/Slf4J Level [" + loggerName + "]=[" + levelName + "], expecting only [ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF] as values.");
                return null;
        }
    }

    public static Level intToLevel(int level)
    {
        if (level >= JettyLogger.OFF)
            return Level.ERROR;
        if (level >= Level.ERROR.toInt())
            return Level.ERROR;
        if (level >= Level.WARN.toInt())
            return Level.WARN;
        if (level >= Level.INFO.toInt())
            return Level.INFO;
        if (level >= Level.DEBUG.toInt())
            return Level.DEBUG;
        if (level >= Level.TRACE.toInt())
            return Level.TRACE;
        return Level.TRACE; // everything else
    }

    public static String levelToString(int level)
    {
        if (level >= JettyLogger.OFF)
            return "OFF";
        if (level >= Level.ERROR.toInt())
            return "ERROR";
        if (level >= Level.WARN.toInt())
            return "WARN";
        if (level >= Level.INFO.toInt())
            return "INFO";
        if (level >= Level.DEBUG.toInt())
            return "DEBUG";
        if (level >= Level.TRACE.toInt())
            return "TRACE";
        return "OFF"; // everything else
    }
}
