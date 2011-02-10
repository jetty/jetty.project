// ========================================================================
// Copyright (c) 2011 Intalio, Inc.
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

package org.eclipse.jetty.io;

import java.net.Socket;

public interface NetworkTrafficListener
{
    public void opened(Socket socket);

    public void incoming(Socket socket, Buffer bytes);

    public void outgoing(Socket socket, Buffer bytes);

    public void closed(Socket socket);

    public static class Empty implements NetworkTrafficListener
    {
        public void opened(Socket socket)
        {
        }

        public void incoming(Socket socket, Buffer bytes)
        {
        }

        public void outgoing(Socket socket, Buffer bytes)
        {
        }

        public void closed(Socket socket)
        {
        }
    }
}
