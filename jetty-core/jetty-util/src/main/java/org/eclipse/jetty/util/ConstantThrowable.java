//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
 * <p>A {@link Throwable} that may be used in static contexts,
 * for example when stored as a {@code static} field.</p>
 * <p>Suppressed exceptions are disabled; adding a suppressed
 * exception has no effect, so that instances of this class
 * stored as {@code static} fields do not accumulate suppressed
 * exceptions.</p>
 * <p>The stack trace is also disabled, since it would only be
 * captured at the time this exception is created, not when the
 * failure actually occurs.</p>
 */
public class ConstantThrowable extends Throwable
{
    public ConstantThrowable()
    {
        this(null);
    }

    public ConstantThrowable(String name)
    {
        super(name, null, false, false);
    }

    @Override
    public String toString()
    {
        return String.valueOf(getMessage());
    }
}
