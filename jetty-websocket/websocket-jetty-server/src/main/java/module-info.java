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

module org.eclipse.jetty.websocket.jetty.server
{
    requires jetty.servlet.api;
    requires org.eclipse.jetty.websocket.core.server;
    requires org.eclipse.jetty.websocket.jetty.common;
    requires org.eclipse.jetty.websocket.servlet;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.webapp;
    requires transitive org.eclipse.jetty.websocket.jetty.api;

    // Only required if using JMX.
    requires static org.eclipse.jetty.jmx;

    exports org.eclipse.jetty.websocket.server;
    exports org.eclipse.jetty.websocket.server.config;

    provides javax.servlet.ServletContainerInitializer with
        org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

    provides org.eclipse.jetty.webapp.Configuration with
        org.eclipse.jetty.websocket.server.config.JettyWebSocketConfiguration;
}
