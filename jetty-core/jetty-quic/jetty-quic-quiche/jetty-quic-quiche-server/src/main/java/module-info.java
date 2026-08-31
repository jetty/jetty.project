//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

module org.eclipse.jetty.quic.quiche.server
{
    requires transitive org.eclipse.jetty.quic.quiche.common;
    requires org.eclipse.jetty.quic.quiche.foreign;
    requires transitive org.eclipse.jetty.quic.server;
    requires transitive org.eclipse.jetty.server;
    requires org.eclipse.jetty.quic.util;

    exports org.eclipse.jetty.quic.quiche.server;
}
