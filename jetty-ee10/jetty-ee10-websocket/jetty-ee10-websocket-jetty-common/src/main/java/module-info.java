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

module org.eclipse.jetty.ee10.websocket.jetty.common
{
    requires org.eclipse.jetty.util;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.websocket.core.common;
    requires transitive org.eclipse.jetty.ee10.websocket.jetty.api;

    exports org.eclipse.jetty.ee10.websocket.common;

    provides org.eclipse.jetty.ee10.websocket.api.ExtensionConfig.Parser with
        org.eclipse.jetty.ee10.websocket.common.ExtensionConfigParser;
}
