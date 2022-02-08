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

package org.eclipse.jetty.io.ssl;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.Connection;

public interface ALPNProcessor
{
    /**
     * Initializes this ALPNProcessor
     *
     * @throws RuntimeException if this processor is unavailable (e.g. missing dependencies or wrong JVM)
     */
    public default void init()
    {
    }

    /**
     * Tests if this processor can be applied to the given SSLEngine.
     *
     * @param sslEngine the SSLEngine to check
     * @return true if the processor can be applied to the given SSLEngine
     */
    public default boolean appliesTo(SSLEngine sslEngine)
    {
        return false;
    }

    /**
     * Configures the given SSLEngine and the given Connection for ALPN.
     *
     * @param sslEngine the SSLEngine to configure
     * @param connection the Connection to configure
     * @throws RuntimeException if this processor cannot be configured
     */
    public default void configure(SSLEngine sslEngine, Connection connection)
    {
    }

    /**
     * Server-side interface used by ServiceLoader.
     */
    public interface Server extends ALPNProcessor
    {
    }

    /**
     * Client-side interface used by ServiceLoader.
     */
    public interface Client extends ALPNProcessor
    {
    }
}
