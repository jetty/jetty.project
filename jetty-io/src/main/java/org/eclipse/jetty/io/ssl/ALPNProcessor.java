//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
