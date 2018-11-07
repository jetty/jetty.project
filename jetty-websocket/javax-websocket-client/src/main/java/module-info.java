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

import javax.websocket.ContainerProvider;

import org.eclipse.jetty.websocket.jsr356.client.JavaxWebSocketClientContainerProvider;

module org.eclipse.jetty.websocket.javax.client {
    exports org.eclipse.jetty.websocket.jsr356.client;

    requires javax.websocket.api;
    requires org.eclipse.jetty.util;
    requires org.eclipse.jetty.http;
    requires org.eclipse.jetty.io;
    requires org.eclipse.jetty.client;
    requires org.eclipse.jetty.websocket.core;
    requires org.eclipse.jetty.websocket.javax.common;

    provides ContainerProvider with JavaxWebSocketClientContainerProvider;
}
