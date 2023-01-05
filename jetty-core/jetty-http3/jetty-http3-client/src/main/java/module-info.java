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

module org.eclipse.jetty.http3.client
{
    requires org.slf4j;

    requires transitive org.eclipse.jetty.http3.common;
    requires transitive org.eclipse.jetty.http3.qpack;
    requires transitive org.eclipse.jetty.io;
    requires transitive org.eclipse.jetty.quic.common;
    requires transitive org.eclipse.jetty.quic.client;
    requires transitive org.eclipse.jetty.util;

    exports org.eclipse.jetty.http3.client;

    exports org.eclipse.jetty.http3.client.internal to
        org.eclipse.jetty.http3.client.transport;
}
