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

import org.eclipse.jetty.ee10.websocket.jakarta.client.webapp.JakartaWebSocketShutdownContainer;

module org.eclipse.jetty.ee10.websocket.jakarta.client.webapp
{
    requires org.slf4j;
    requires transitive jakarta.servlet;
    requires transitive org.eclipse.jetty.ee10.websocket.jakarta.client;

    exports org.eclipse.jetty.ee10.websocket.jakarta.client.webapp;

    provides jakarta.servlet.ServletContainerInitializer with
        JakartaWebSocketShutdownContainer;
}
