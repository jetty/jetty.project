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

module org.eclipse.jetty.http2.common
{
    requires org.slf4j;

    requires transitive org.eclipse.jetty.http2.hpack;
    requires jdk.unsupported;

    exports org.eclipse.jetty.http2;
    exports org.eclipse.jetty.http2.api;
    exports org.eclipse.jetty.http2.api.server;
    exports org.eclipse.jetty.http2.frames;

    exports org.eclipse.jetty.http2.internal to org.eclipse.jetty.http2.client, org.eclipse.jetty.http2.http.client.transport, org.eclipse.jetty.http2.server;
    exports org.eclipse.jetty.http2.internal.generator to org.eclipse.jetty.http2.client, org.eclipse.jetty.http2.http.client.transport, org.eclipse.jetty.http2.server;
    exports org.eclipse.jetty.http2.internal.parser to org.eclipse.jetty.http2.client, org.eclipse.jetty.http2.http.client.transport, org.eclipse.jetty.http2.server;
    exports org.eclipse.jetty.http2.internal.jctools to org.eclipse.jetty.http2.client, org.eclipse.jetty.http2.http.client.transport, org.eclipse.jetty.http2.server;
}
