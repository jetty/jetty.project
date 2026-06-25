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

module org.eclipse.jetty.quic.common
{
    requires org.eclipse.jetty.io;
    requires transitive org.eclipse.jetty.quic.api;
    requires transitive org.eclipse.jetty.quic.util;
    requires org.eclipse.jetty.tls.common;
    requires org.slf4j;
    requires java.desktop;

    exports org.eclipse.jetty.quic.common;
    exports org.eclipse.jetty.quic.common.frames;
    exports org.eclipse.jetty.quic.common.packets;
    exports org.eclipse.jetty.quic.common.tls;
    exports org.eclipse.jetty.quic.common.tls.ext;
    exports org.eclipse.jetty.quic.common.tls.parser;
    exports org.eclipse.jetty.quic.common.tls.generator;
}
