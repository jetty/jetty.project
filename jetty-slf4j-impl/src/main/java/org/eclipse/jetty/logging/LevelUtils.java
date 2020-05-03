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
    public static Integer getLevelInt(String levelStr)
    {
        try
        {
            if (levelStr == null)
                return null;
            String levelName = levelStr.trim().toUpperCase(Locale.ENGLISH);
            if ("ALL".equals(levelName))
                return Level.TRACE.toInt();
            return Level.valueOf(levelName).toInt();
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    public static Level intToLevel(int level)
    {
        try
        {
            if (level < JettyLogger.ALL)
                return Level.TRACE;
            return Level.intToLevel(level);
        }
        catch (Throwable x)
        {
            return Level.ERROR;
        }
    }
}
