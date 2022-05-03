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

module org.eclipse.jetty.ee9.websocket.jetty.client
{
    requires org.eclipse.jetty.websocket.core.client;
    requires org.eclipse.jetty.ee9.websocket.jetty.common;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.client;
    requires transitive org.eclipse.jetty.ee9.websocket.jetty.api;

    requires static org.eclipse.jetty.ee9.webapp;

    exports org.eclipse.jetty.ee9.websocket.client;
}
