//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Logger;

public class DummyLocalEndpoint implements WebSocketLocalEndpoint.Adaptor
{
    private boolean open = false;

    @Override
    public Logger getLog()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return open;
    }

    @Override
    public void onOpen(WebSocketRemoteEndpoint remote)
    {
        open = true;
    }
}
