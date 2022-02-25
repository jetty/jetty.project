//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.messages;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * Sink consumer for messages (used for multiple frames with continuations,
 * and also to allow for streaming APIs)
 */
public interface MessageSink
{
    /**
     * Consume the frame payload to the message.
     *
     * @param frame the frame, its payload (and fin state) to append
     * @param callback the callback for how the frame was consumed
     */
    void accept(Frame frame, Callback callback);
}
