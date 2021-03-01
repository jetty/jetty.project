//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

import jakarta.websocket.ContainerProvider;
import org.eclipse.jetty.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;

module org.eclipse.jetty.websocket.jakarta.client
{
    exports org.eclipse.jetty.websocket.jakarta.client;
    exports org.eclipse.jetty.websocket.jakarta.client.internal to org.eclipse.jetty.websocket.jakarta.server;

    requires static jetty.servlet.api;
    requires org.slf4j;
    requires org.eclipse.jetty.websocket.core.client;
    requires org.eclipse.jetty.websocket.jakarta.common;
    requires transitive org.eclipse.jetty.client;
    requires transitive jetty.websocket.api;

    provides ContainerProvider with JakartaWebSocketClientContainerProvider;
}
