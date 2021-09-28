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

module org.eclipse.jetty.websocket.jakarta.server
{
    requires org.eclipse.jetty.websocket.core.server;
    requires org.eclipse.jetty.websocket.jakarta.common;
    requires org.eclipse.jetty.websocket.servlet;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.webapp;
    requires transitive org.eclipse.jetty.websocket.jakarta.client;

    exports org.eclipse.jetty.websocket.jakarta.server.config;

    provides jakarta.servlet.ServletContainerInitializer with
        org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;

    provides jakarta.websocket.server.ServerEndpointConfig.Configurator with
        org.eclipse.jetty.websocket.jakarta.server.config.ContainerDefaultConfigurator;

    provides  org.eclipse.jetty.webapp.Configuration with
        org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketConfiguration;
}
