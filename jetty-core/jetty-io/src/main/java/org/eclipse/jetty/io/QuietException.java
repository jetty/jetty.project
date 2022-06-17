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

package org.eclipse.jetty.io;

/**
 * A Quiet Exception.
 * <p> Exception classes that extend this interface will be logged
 * less verbosely.
 */
public interface QuietException
{
    static boolean isQuiet(Throwable throwable)
    {
        while (throwable != null)
        {
            if (throwable instanceof QuietException)
                return true;
            throwable = throwable.getCause();
        }
        return false;
    }

    class Exception extends java.lang.Exception implements QuietException
    {
        public Exception()
        {
            super();
        }

        public Exception(String message)
        {
            super(message);
        }

        public Exception(String message, Throwable cause)
        {
            super(message, cause);
        }

        public Exception(Throwable cause)
        {
            super(cause);
        }
    }
}
