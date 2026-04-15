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

package org.eclipse.jetty.ee10.servlets;

/**
 * <p>{@link EventSource} is the passive half of an event source connection, as defined by the
 * <a href="http://www.w3.org/TR/eventsource/">EventSource Specification</a>.</p>
 * <p>{@link EventSource.Emitter} is the active half of the connection and allows to operate on the connection.</p>
 * <p>{@link EventSource} allows applications to be notified of events happening on the connection;
 * two events are being notified: the opening of the event source connection, where method
 * {@link EventSource#onOpen(Emitter)} is invoked, and the closing of the event source connection,
 * where method {@link EventSource#onClose()} is invoked.</p>
 *
 * @see EventSourceServlet
 */
public interface EventSource
    extends org.eclipse.jetty.ee.servlets.EventSource
{
}
