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

module org.eclipse.jetty.osgi
{

    requires org.eclipse.jetty.deploy;
    requires org.eclipse.jetty.xml;
    requires org.eclipse.osgi;
    requires org.eclipse.osgi.services;
    requires org.osgi.service.event;

    exports org.eclipse.jetty.osgi;
}
