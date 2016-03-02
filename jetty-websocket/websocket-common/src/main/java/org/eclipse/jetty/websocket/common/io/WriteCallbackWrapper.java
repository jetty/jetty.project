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

package org.eclipse.jetty.websocket.common.io;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.WriteCallback;

/**
 * Wraps the exposed {@link WriteCallback} WebSocket API with a Jetty {@link Callback}.
 * <p>
 * We don't expose the jetty {@link Callback} object to the webapp, as that makes things complicated for the WebAppContext's Classloader.
 */
public class WriteCallbackWrapper implements Callback
{
    public static Callback wrap(WriteCallback callback)
    {
        if (callback == null)
        {
            return null;
        }
        return new WriteCallbackWrapper(callback);
    }

    private final WriteCallback callback;

    public WriteCallbackWrapper(WriteCallback callback)
    {
        this.callback = callback;
    }

    @Override
    public void failed(Throwable x)
    {
        callback.writeFailed(x);
    }

    @Override
    public void succeeded()
    {
        callback.writeSuccess();
    }
}
