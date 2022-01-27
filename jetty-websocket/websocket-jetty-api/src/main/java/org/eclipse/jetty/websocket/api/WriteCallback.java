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

package org.eclipse.jetty.websocket.api;

/**
 * Callback for Write events.
 * <p>
 * NOTE: We don't expose org.eclipse.jetty.util.Callback here as that would complicate matters with the WebAppContext's classloader isolation.
 */
public interface WriteCallback
{
    WriteCallback NOOP = new Adaptor();

    /**
     * <p>
     * Callback invoked when the write fails.
     * </p>
     *
     * @param x the reason for the write failure
     */
    default void writeFailed(Throwable x)
    {
    }

    /**
     * <p>
     * Callback invoked when the write succeeds.
     * </p>
     *
     * @see #writeFailed(Throwable)
     */
    default void writeSuccess()
    {
    }

    class Adaptor implements WriteCallback
    {
        @Override
        public void writeFailed(Throwable x)
        {
        }

        @Override
        public void writeSuccess()
        {
        }
    }
}
