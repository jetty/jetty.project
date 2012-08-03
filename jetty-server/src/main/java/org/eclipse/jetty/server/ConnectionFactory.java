// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

/**
 * <p>Factory for {@link Connection}s.</p>
 */
public interface ConnectionFactory
{
    /**
     * <p>Creates a new {@link Connection} with the given parameters</p>
     * @param channel the {@link SocketChannel} associated with the connection
     * @param endPoint the {@link EndPoint} associated with the connection
     * @param attachment the attachment associated with the connection
     * @return a new {@link Connection}
     */
    public Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment);
}
