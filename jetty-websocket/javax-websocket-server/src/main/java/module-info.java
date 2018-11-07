//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

import javax.servlet.ServletContainerInitializer;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.websocket.jsr356.server.ContainerDefaultConfigurator;
import org.eclipse.jetty.websocket.jsr356.server.JavaxWebSocketConfiguration;
import org.eclipse.jetty.websocket.jsr356.server.JavaxWebSocketServerContainerInitializer;

module org.eclipse.jetty.websocket.javax.server {
    exports org.eclipse.jetty.websocket.jsr356.server;

    requires javax.servlet.api;
    requires javax.websocket.api;
    requires org.eclipse.jetty.util;
    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.io;
    requires org.eclipse.jetty.client;
    requires org.eclipse.jetty.server;
    requires org.eclipse.jetty.servlet;
    requires org.eclipse.jetty.webapp;
    requires org.eclipse.jetty.websocket.core;
    requires org.eclipse.jetty.websocket.javax.common;
    requires org.eclipse.jetty.websocket.javax.client;
    requires org.eclipse.jetty.websocket.servlet;

    provides ServletContainerInitializer with JavaxWebSocketServerContainerInitializer;
    provides ServerEndpointConfig.Configurator with ContainerDefaultConfigurator;
    provides Configuration with JavaxWebSocketConfiguration;
}
