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

package org.eclipse.jetty.ee11.servlets;

import jakarta.servlet.http.HttpServletRequest;

/**
 * <p>A servlet that implements the <a href="http://www.w3.org/TR/eventsource/">event source protocol</a>,
 * also known as "server sent events".</p>
 * <p>This servlet must be subclassed to implement abstract method {@link #newEventSource(HttpServletRequest)}
 * to return an instance of {@link EventSource} that allows application to listen for event source events
 * and to emit event source events.</p>
 * <p>This servlet supports the following configuration parameters:</p>
 * <ul>
 * <li><code>heartBeatPeriod</code>, that specifies the heartbeat period, in seconds, used to check
 * whether the connection has been closed by the client; defaults to 10 seconds.</li>
 * </ul>
 *
 * <p>NOTE: there is currently no support for <code>last-event-id</code>.</p>
 */
public abstract class EventSourceServlet extends org.eclipse.jetty.ee.servlets.EventSourceServlet
{
}
