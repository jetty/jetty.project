//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.servlet;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests.
 * <p>
 * The configuration applied to this filter via init params will be used as the the default
 * configuration of any websocket upgraded by this filter, prior to the configuration of the
 * websocket applied by the {@link WebSocketMappings}.
 * </p>
 * <p>
 * <b>Configuration / Init-Parameters:</b>
 * </p>
 * <dl>
 * <dt>idleTimeout</dt>
 * <dd>set the time in ms that a websocket may be idle before closing<br>
 * <dt>maxTextMessageSize</dt>
 * <dd>set the size in UTF-8 bytes that a websocket may be accept as a Text Message before closing<br>
 * <dt>maxBinaryMessageSize</dt>
 * <dd>set the size in bytes that a websocket may be accept as a Binary Message before closing<br>
 * <dt>inputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to read raw bytes from the network layer<br>
 * <dt>outputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to write bytes to the network layer<br>
 * <dt>maxFrameSize</dt>
 * <dd>The maximum frame size sent or received.<br>
 * <dt>autoFragment</dt>
 * <dd>If true, frames are automatically fragmented to respect the maximum frame size.<br>
 * </dl>
 */
@ManagedObject("WebSocket Upgrade Filter")
public class WebSocketUpgradeFilter extends org.eclipse.jetty.ee.websocket.servlet.WebSocketUpgradeFilter
{
}
