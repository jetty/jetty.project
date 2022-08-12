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

package org.eclipse.jetty.websocket.core.extensions;

import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractExtensionTest
{
    public WebSocketComponents components = new WebSocketComponents();

    protected ExtensionTool clientExtensions;
    protected ExtensionTool serverExtensions;

    @BeforeEach
    public void init()
    {
        clientExtensions = new ExtensionTool(components.getBufferPool());
        serverExtensions = new ExtensionTool(components.getBufferPool());
    }
}
