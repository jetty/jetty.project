//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.SocketAddress;
import java.util.Map;

/**
 * <p>The abstraction that client components implement to
 * provide a service that connects to remote hosts.</p>
 */
public interface Connectable
{
    public static final String CLIENT_CONNECTOR_CONTEXT_KEY = "org.eclipse.jetty.client.connector";
    public static final String CONNECTION_PROMISE_CONTEXT_KEY = CLIENT_CONNECTOR_CONTEXT_KEY + ".connectionPromise";

    /**
     * <p>Connects to a remote hosts using the information provided
     * by the given {@code address} and {@code context} map.</p>
     * <p>The connection may not be established to the given socket address.</p>
     * <p>Implementations must arrange to notify the {@code Promise<org.eclipse.jetty.io.Connection>},
     * present in the {@code context} map under the {@link #CONNECTION_PROMISE_CONTEXT_KEY} key,
     * both in case of successful connection or in case of connection failure.</p>
     *
     * @param address the socket address
     * @param context the context map
     */
    public void connect(SocketAddress address, Map<String, Object> context);
}
