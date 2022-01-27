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

module org.eclipse.jetty.quic.quiche.jna
{
    requires com.sun.jna;
    requires org.eclipse.jetty.quic.quiche;
    requires org.eclipse.jetty.util;
    requires org.slf4j;

    // Allow JNA to use reflection on the implementation classes.
    opens org.eclipse.jetty.quic.quiche.jna;
    opens org.eclipse.jetty.quic.quiche.jna.linux;
    opens org.eclipse.jetty.quic.quiche.jna.macos;
    opens org.eclipse.jetty.quic.quiche.jna.windows;

    provides org.eclipse.jetty.quic.quiche.QuicheBinding with
        org.eclipse.jetty.quic.quiche.jna.JnaQuicheBinding;
}
