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

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Appender for messages (used for multiple frames with continuations, and also to allow for streaming APIs)
 */
public interface MessageAppender
{
    /**
     * Append the frame payload to the message.
     *
     * @param framePayload the frame payload to append.
     * @param isLast flag indicating if this is the last part of the message or not.
     * @throws IOException if unable to append the frame payload
     */
    void appendFrame(ByteBuffer framePayload, boolean isLast) throws IOException;

    /**
     * Notification that message is to be considered complete.
     * <p>
     * Any cleanup or final actions should be taken here.
     */
    void messageComplete();
}
