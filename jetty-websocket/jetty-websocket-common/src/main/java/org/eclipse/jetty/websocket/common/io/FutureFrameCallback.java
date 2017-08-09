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

package org.eclipse.jetty.websocket.common.io;

import java.util.concurrent.Future;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.FrameCallback;

/**
 * Allows events to a {@link FrameCallback} to drive a {@link Future} for the internals.
 */
public class FutureFrameCallback extends FutureCallback implements FrameCallback
{
    @Override
    public void fail(Throwable cause)
    {
        failed(cause);
    }
    
    @Override
    public void succeed()
    {
        succeeded();
    }
}
