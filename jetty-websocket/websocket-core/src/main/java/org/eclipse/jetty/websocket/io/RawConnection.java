// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Interface for working with connections in a raw way.
 * <p>
 * This is abstracted out to allow for common access to connection internals regardless of physical vs virtual connections.
 */
public interface RawConnection extends OutgoingFrames
{
    void close() throws IOException;

    void close(int statusCode, String reason) throws IOException;

    void disconnect(boolean onlyOutput);

    InetSocketAddress getRemoteAddress();

    boolean isOpen();
}
