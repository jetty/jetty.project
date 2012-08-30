//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.io.message;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Appender for messages (used for multiple fragments with continuations, and also to allow for streaming APIs)
 */
public interface MessageAppender
{
    /**
     * Append the payload to the message.
     * 
     * @param payload
     *            the payload to append.
     * @throws IOException
     *             if unable to append the payload
     */
    abstract void appendMessage(ByteBuffer payload) throws IOException;

    /**
     * Notification that message is to be considered complete.
     */
    abstract void messageComplete();
}
