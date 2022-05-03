//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ant.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.tools.ant.Task;

/**
 * Provides logging functionality for classes without access to the Ant project
 * variable.
 */
public class TaskLog
{

    private static Task task;

    private static final SimpleDateFormat format = new SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss.SSS");

    public static void setTask(Task task)
    {
        TaskLog.task = task;
    }

    public static void log(String message)
    {
        task.log(message);
    }

    public static void logWithTimestamp(String message)
    {
        String date;
        synchronized (format)
        {
            date = format.format(new Date());
        }
        task.log(date + ": " + message);
    }
}
