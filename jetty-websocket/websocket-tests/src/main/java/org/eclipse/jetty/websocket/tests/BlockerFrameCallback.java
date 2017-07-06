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

package org.eclipse.jetty.websocket.tests;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.FrameCallback;

public class BlockerFrameCallback implements FrameCallback
{
    private CompletableFuture<Void> future = new CompletableFuture<>();
    
    @Override
    public void fail(Throwable cause)
    {
        future.completeExceptionally(cause);
    }
    
    @Override
    public void succeed()
    {
        future.complete(null);
    }
    
    public void block() throws Exception
    {
        future.get(1, TimeUnit.MINUTES);
    }
}