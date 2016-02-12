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

package org.eclipse.jetty.websocket.common.message;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/**
 * Sink consumer for messages (used for multiple frames with continuations, 
 * and also to allow for streaming APIs)
 */
public interface MessageSink extends BiConsumer<ByteBuffer, Boolean>
{
    /**
     * Consume the frame payload to the message.
     * 
     * @param payload
     *            the frame payload to append.
     * @param fin
     *            flag indicating if this is the final part of the message or not.
     */
    @Override
    void accept(ByteBuffer payload, Boolean fin);
}
