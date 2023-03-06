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

import org.eclipse.jetty.websocket.common.ExtensionConfigParser;

module org.eclipse.jetty.websocket.common
{
    requires org.eclipse.jetty.util;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.websocket.core.common;
    requires transitive org.eclipse.jetty.websocket.api;

    exports org.eclipse.jetty.websocket.common;

    provides org.eclipse.jetty.websocket.api.ExtensionConfig.Parser with
        ExtensionConfigParser;
}
