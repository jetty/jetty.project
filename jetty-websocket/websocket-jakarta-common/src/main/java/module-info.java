//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

module org.eclipse.jetty.websocket.jakarta.common
{
    exports org.eclipse.jetty.websocket.jakarta.common;
    exports org.eclipse.jetty.websocket.jakarta.common.decoders;
    exports org.eclipse.jetty.websocket.jakarta.common.encoders;
    exports org.eclipse.jetty.websocket.jakarta.common.messages;

    requires transitive jetty.websocket.api;
    requires transitive org.eclipse.jetty.http;
    requires transitive org.eclipse.jetty.io;
    requires transitive org.eclipse.jetty.websocket.core;
    requires transitive org.eclipse.jetty.websocket.util;
    requires org.slf4j;
}
