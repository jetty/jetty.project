//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class CompletableFutureCallback extends CompletableFuture<Callback> implements Callback
{
    private static final Logger LOG = Log.getLogger(CompletableFutureCallback.class);
    
    @Override
    public void failed(Throwable cause)
    {
        if(LOG.isDebugEnabled())
            LOG.debug("failed()", cause);
        
        completeExceptionally(cause);
    }
    
    @Override
    public void succeeded()
    {
        if(LOG.isDebugEnabled())
            LOG.debug("succeeded()");
        
        complete(this);
    }
}
