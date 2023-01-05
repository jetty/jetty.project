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

module org.eclipse.jetty.http3.common
{
    requires org.eclipse.jetty.io;
    requires org.eclipse.jetty.util;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.http;
    requires transitive org.eclipse.jetty.http3.qpack;
    requires transitive org.eclipse.jetty.quic.common;

    exports org.eclipse.jetty.http3;
    exports org.eclipse.jetty.http3.api;
    exports org.eclipse.jetty.http3.frames;

    exports org.eclipse.jetty.http3.internal to
        org.eclipse.jetty.http3.client,
        org.eclipse.jetty.http3.server,
        org.eclipse.jetty.http3.client.transport;
    exports org.eclipse.jetty.http3.internal.generator to
        org.eclipse.jetty.http3.client,
        org.eclipse.jetty.http3.server;
    exports org.eclipse.jetty.http3.internal.parser to
        org.eclipse.jetty.http3.client,
        org.eclipse.jetty.http3.server;
}
