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

package org.eclipse.jetty.client;

/**
 * <p>A destination for those network transports that are duplex (e.g. HTTP/1.1 and FastCGI).</p>
 *
 * @see MultiplexHttpDestination
 */
public class DuplexHttpDestination extends HttpDestination
{
    public DuplexHttpDestination(HttpClient client, Origin origin)
    {
        this(client, origin, false);
    }

    public DuplexHttpDestination(HttpClient client, Origin origin, boolean intrinsicallySecure)
    {
        super(client, origin, intrinsicallySecure);
    }
}
