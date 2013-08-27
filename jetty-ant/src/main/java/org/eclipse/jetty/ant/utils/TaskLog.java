//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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

package org.eclipse.jetty.ant.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.tools.ant.Task;

/**
 * Provides logging functionality for classes without access to the Ant project
 * variable.
 * 
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
