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

module org.eclipse.jetty.ee9.jaas
{
    requires org.slf4j;
    requires org.eclipse.jetty.util;

    requires transitive org.eclipse.jetty.ee9.security;

    // Only required if using JDBCLoginModule.
    requires static java.sql;

    exports org.eclipse.jetty.ee9.jaas;
    exports org.eclipse.jetty.ee9.jaas.callback;
    exports org.eclipse.jetty.ee9.jaas.spi;
}
