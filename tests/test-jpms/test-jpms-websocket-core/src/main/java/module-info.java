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

/**
 * This module-info.java exists so that the tests can be run in JPMS mode,
 * therefore testing the JPMS module descriptors of the dependencies involved.
 */
module org.eclipse.jetty.websocket.core.tests
{
    requires org.eclipse.jetty.websocket.core.client;
    requires org.eclipse.jetty.websocket.core.server;
}
