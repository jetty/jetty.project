//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.api;

import java.util.concurrent.Future;

import org.eclipse.jetty.client.HttpClient;

/**
 * {@link Destination} represents the triple made of the {@link #getScheme}, the {@link #getHost}
 * and the {@link #getPort}.
 * <p />
 * {@link Destination} holds a pool of {@link Connection}s, but allows to create unpooled
 * connections if the application wants full control over connection management via {@link #newConnection()}.
 * <p />
 * {@link Destination}s may be obtained via {@link HttpClient#getDestination(String, String, int)}
 */
public interface Destination
{
    /**
     * @return the scheme of this destination, such as "http" or "https"
     */
    String getScheme();

    /**
     * @return the host of this destination, such as "127.0.0.1" or "google.com"
     */
    String getHost();

    /**
     * @return the port of this destination such as 80 or 443
     */
    int getPort();

    /**
     * @return a future to a new, unpooled, {@link Connection}
     */
    Future<Connection> newConnection();
}
