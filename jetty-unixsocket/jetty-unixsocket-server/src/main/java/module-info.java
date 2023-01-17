//
// ========================================================================
// Copyright (c) 1995-2023 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

@Deprecated(forRemoval = true)
module org.eclipse.jetty.unixsocket.server
{
    requires org.slf4j;

    requires transitive org.eclipse.jetty.server;
    requires transitive org.eclipse.jetty.unixsocket.common;
    requires transitive org.jnrproject.enxio;
    requires transitive org.jnrproject.unixsocket;

    exports org.eclipse.jetty.unixsocket.server;
}
