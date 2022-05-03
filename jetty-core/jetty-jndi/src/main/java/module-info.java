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

module org.eclipse.jetty.jndi
{
    requires org.slf4j;

    requires transitive java.naming;
    requires transitive org.eclipse.jetty.server;

    // Only required if using MailSessionReference.
    // requires static jakarta.mail;
    // Only required if using DataSourceCloser.
    requires static java.sql;
    // requires static org.eclipse.jetty.security;

    exports org.eclipse.jetty.jndi;
    exports org.eclipse.jetty.jndi.factories;
    exports org.eclipse.jetty.jndi.java;
    exports org.eclipse.jetty.jndi.local;
}
