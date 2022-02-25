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

/**
 * A Usage Error has occurred. Print the usage and exit with the appropriate exit code.
 */
@SuppressWarnings("serial")
public class UsageException extends RuntimeException
{
    public static final int ERR_LOGGING = -1;
    public static final int ERR_INVOKE_MAIN = -2;
    public static final int ERR_NOT_STOPPED = -4;
    public static final int ERR_BAD_ARG = -5;
    public static final int ERR_BAD_GRAPH = -6;
    public static final int ERR_BAD_STOP_PROPS = -7;
    public static final int ERR_UNKNOWN = -9;
    private int exitCode;

    public UsageException(int exitCode, String message)
    {
        super(message);
        this.exitCode = exitCode;
    }

    public UsageException(int exitCode, String format, Object... objs)
    {
        super(String.format(format, objs));
        this.exitCode = exitCode;
    }

    public UsageException(String format, Object... objs)
    {
        this(ERR_UNKNOWN, format, objs);
    }

    public UsageException(int exitCode, Throwable cause)
    {
        super(cause);
        this.exitCode = exitCode;
    }

    public int getExitCode()
    {
        return exitCode;
    }
}
