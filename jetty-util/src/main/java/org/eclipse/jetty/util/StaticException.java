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

package org.eclipse.jetty.util;

/**
 * This exception can safely be stored in a static variable as suppressed exceptions are disabled,
 * meaning calling {@link #addSuppressed(Throwable)} has no effect.
 * This prevents potential memory leaks where a statically-stored exception would accumulate
 * suppressed exceptions added to them.
 */
public class StaticException extends Exception
{
    public StaticException()
    {
        this(null);
    }

    public StaticException(String message)
    {
        // Make sure to call the super constructor that disables suppressed exception.
        super(message, null, false, true);
    }
}
