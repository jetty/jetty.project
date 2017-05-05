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

package org.eclipse.jetty.websocket.common.function;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;

/**
 * The interface a WebSocket Connection and Session has to the User provided Endpoint.
 *
 * @param <T> the Session object
 */
public interface EndpointFunctions<T> extends LifeCycle
{
    Logger getLog();
    
    T getSession();
    
    void onOpen(T session);

    void onClose(CloseInfo close);

    void onFrame(Frame frame);

    void onError(Throwable cause);

    void onText(Frame frame, FrameCallback callback);

    void onBinary(Frame frame, FrameCallback callback);
    
    void onContinuation(Frame frame, FrameCallback callback);
    
    void onPing(ByteBuffer payload);

    void onPong(ByteBuffer payload);
}
