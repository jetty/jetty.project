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

package org.eclipse.jetty.websocket.common.message;

import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;

/**
 * Sink consumer for messages (used for multiple frames with continuations, 
 * and also to allow for streaming APIs)
 */
public interface MessageSink
{
    /**
     * Consume the frame payload to the message.
     * 
     * @param frame
     *            the frame, its payload (and fin state) to append
     * @param callback
     *            the callback for how the frame was consumed
     */
    void accept(Frame frame, FrameCallback callback);
}
