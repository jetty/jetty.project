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

package org.eclipse.jetty.websocket.common.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketSession;

public interface EventDriver extends IncomingFrames
{
    public WebSocketPolicy getPolicy();

    public WebSocketSession getSession();
    
    public default BatchMode getBatchMode()
    {
        return null;
    }

    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException;

    public void onBinaryMessage(byte[] data);

    public void onClose(CloseInfo close);

    public void onConnect();

    public void onContinuationFrame(ByteBuffer buffer, boolean fin) throws IOException;

    public void onError(Throwable t);

    public default void onFrame(Frame frame)
    {
        /* Ignored, not supported by JSR-356 */
    }

    public void onInputStream(InputStream stream) throws IOException;

    public default void onPing(ByteBuffer buffer)
    {
        /* TODO: provide annotation in future */
    }
    
    public default void onPong(ByteBuffer buffer)
    {
        /* TODO: provide annotation in future */
    }

    public void onReader(Reader reader) throws IOException;

    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException;

    public void onTextMessage(String message);

    public void openSession(WebSocketSession session);
}
