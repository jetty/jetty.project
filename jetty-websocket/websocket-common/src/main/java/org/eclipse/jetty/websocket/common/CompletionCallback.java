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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.FrameCallback;

/**
 * A callback which will trigger complete regardless of success/failure.
 */
public abstract class CompletionCallback implements FrameCallback
{
    private static final Logger LOG = Log.getLogger(CompletionCallback.class);
    
    @Override
    public void fail(Throwable cause)
    {
        LOG.ignore(cause);
        complete();
    }
    
    @Override
    public void succeed()
    {
        complete();
    }
    
    public abstract void complete();
}
