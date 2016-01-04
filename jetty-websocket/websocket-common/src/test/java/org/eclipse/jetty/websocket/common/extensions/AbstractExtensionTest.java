//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public abstract class AbstractExtensionTest
{
    @Rule
    public TestName testname = new TestName();
    
    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    protected ExtensionTool clientExtensions;
    protected ExtensionTool serverExtensions;

    @Before
    public void init()
    {
        clientExtensions = new ExtensionTool(WebSocketPolicy.newClientPolicy(),bufferPool);
        serverExtensions = new ExtensionTool(WebSocketPolicy.newServerPolicy(),bufferPool);
    }
}
