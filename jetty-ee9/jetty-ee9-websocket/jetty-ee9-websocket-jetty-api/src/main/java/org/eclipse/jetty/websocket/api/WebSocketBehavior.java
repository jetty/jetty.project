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

package org.eclipse.jetty.websocket.api;

/**
 * Behavior for how the WebSocket should operate.
 * <p>
 * This dictated by the <a href="https://tools.ietf.org/html/rfc6455">RFC 6455</a> spec in various places, where certain behavior must be performed depending on
 * operation as a <a href="https://tools.ietf.org/html/rfc6455#section-4.1">CLIENT</a> vs a <a href="https://tools.ietf.org/html/rfc6455#section-4.2">SERVER</a>
 */
public enum WebSocketBehavior
{
    CLIENT,
    SERVER
}
