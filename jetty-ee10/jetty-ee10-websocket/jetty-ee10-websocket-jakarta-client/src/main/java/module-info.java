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

module org.eclipse.jetty.ee10.websocket.jakarta.client
{
    requires org.eclipse.jetty.websocket.core.client;
    requires org.eclipse.jetty.ee10.websocket.jakarta.common;
    requires org.slf4j;

    requires transitive jakarta.websocket;
    requires transitive org.eclipse.jetty.client;

    requires static  jakarta.servlet;

    exports org.eclipse.jetty.ee10.websocket.jakarta.client;

    exports org.eclipse.jetty.ee10.websocket.jakarta.client.internal to
        org.eclipse.jetty.ee10.websocket.jakarta.server;

    provides jakarta.websocket.ContainerProvider with
        org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;
}
