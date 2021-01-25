//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.extensions;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractExtensionTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    protected ExtensionTool clientExtensions;
    protected ExtensionTool serverExtensions;

    @BeforeEach
    public void init()
    {
        clientExtensions = new ExtensionTool(WebSocketPolicy.newClientPolicy(), bufferPool);
        serverExtensions = new ExtensionTool(WebSocketPolicy.newServerPolicy(), bufferPool);
    }
}
