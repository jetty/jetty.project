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

package org.eclipse.jetty.websocket.core.util;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A callback which will trigger complete regardless of success/failure.
 */
public abstract class CompletionCallback implements Callback
{
    private static final Logger LOG = Log.getLogger(CompletionCallback.class);

    @Override
    public void failed(Throwable cause)
    {
        LOG.ignore(cause);
        complete();
    }

    @Override
    public void succeeded()
    {
        complete();
    }
    
    public abstract void complete();
}
