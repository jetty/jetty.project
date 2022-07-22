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

package org.eclipse.jetty.server;

import org.eclipse.jetty.io.EndPoint;

/**
 * <p>Supports the implementation of HTTP {@code CONNECT} tunnels.</p>
 */
public interface TunnelSupport
{
    /**
     * <p>Returns the protocol of the {@code CONNECT} tunnel,
     * or {@code null} if the tunnel transports HTTP or opaque bytes.</p>
     *
     * @return the {@code CONNECT} tunnel protocol, or {@code null} for HTTP
     */
    String getProtocol();

    /**
     * <p>Returns the {@link EndPoint} that should be used to carry the
     * tunneled protocol.</p>
     *
     * @return the {@code CONNECT} tunnel {@link EndPoint}
     */
    EndPoint getEndPoint();
}
