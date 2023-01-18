//
// ========================================================================
// Copyright (c) 2019 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

module org.eclipse.jetty.security
{
    requires org.slf4j;

    requires transitive org.eclipse.jetty.server;

    // Only required if using SPNEGO.
    requires static java.security.jgss;
    // Only required if using JDBCLoginService.
    requires static java.sql;

    exports org.eclipse.jetty.security;
    exports org.eclipse.jetty.security.authentication;

    uses org.eclipse.jetty.security.Authenticator.Factory;
}

