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

package org.eclipse.jetty.websocket.api;

public interface FrameCallback
{
    /**
     * <p>
     * Callback invoked when the frame fails.
     * </p>
     *
     * @param cause the reason for the frame failure
     */
    void fail(Throwable cause);
    
    /**
     * <p>
     * Callback invoked when the frame read/write completes.
     * </p>
     *
     * @see #fail(Throwable)
     */
    void succeed();
    
    class Adapter implements FrameCallback
    {
        @Override
        public void fail(Throwable cause)
        {
        }
        
        @Override
        public void succeed()
        {
        }
    }
}
