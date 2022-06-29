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

package org.eclipse.jetty.ee10.test.support.rawhttp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * A Raw HTTP Socket connection interface.
 */
public interface HttpSocket
{
    Socket connect(InetAddress host, int port) throws IOException;
}
