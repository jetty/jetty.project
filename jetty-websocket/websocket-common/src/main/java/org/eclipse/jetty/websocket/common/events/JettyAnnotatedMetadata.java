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

package org.eclipse.jetty.websocket.common.events;

import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.OptionalSessionCallableMethod;

public class JettyAnnotatedMetadata
{
    /**
     * {@code @OnWebSocketConnect ()}
     */
    public CallableMethod onConnect;
    /**
     * {@code @OnWebSocketMessage (byte[], or ByteBuffer, or InputStream)}
     */
    public OptionalSessionCallableMethod onBinary;
    /**
     * {@code @OnWebSocketMessage (String, or Reader)}
     */
    public OptionalSessionCallableMethod onText;
    /**
     * {@code @OnWebSocketFrame (Frame)}
     */
    public OptionalSessionCallableMethod onFrame;
    /**
     * {@code @OnWebSocketError (Throwable)}
     */
    public OptionalSessionCallableMethod onError;
    /**
     * {@code @OnWebSocketClose (Frame)}
     */
    public OptionalSessionCallableMethod onClose;

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append(this.getClass().getSimpleName());
        s.append("[");
        s.append("onConnect=").append(onConnect);
        s.append(",onBinary=").append(onBinary);
        s.append(",onText=").append(onText);
        s.append(",onFrame=").append(onFrame);
        s.append(",onError=").append(onError);
        s.append(",onClose=").append(onClose);
        s.append("]");
        return s.toString();
    }
}
