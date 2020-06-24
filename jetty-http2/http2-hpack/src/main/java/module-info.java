//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

import org.eclipse.jetty.http.HttpFieldPreEncoder;
import org.eclipse.jetty.http2.hpack.HpackFieldPreEncoder;

module org.eclipse.jetty.http2.hpack
{
    exports org.eclipse.jetty.http2.hpack;

    requires transitive org.eclipse.jetty.http;
    requires org.slf4j;

    provides HttpFieldPreEncoder with HpackFieldPreEncoder;
}
