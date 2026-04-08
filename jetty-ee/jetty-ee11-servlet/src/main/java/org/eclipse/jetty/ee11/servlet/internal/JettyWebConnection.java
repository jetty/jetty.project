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

package org.eclipse.jetty.ee11.servlet.internal;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.WebConnection;
import org.eclipse.jetty.util.IO;

public record JettyWebConnection(ServletInputStream inputStream, ServletOutputStream outputStream) implements WebConnection
{
    @Override
    public void close()
    {
        IO.close(inputStream);
        IO.close(outputStream);
    }

    @Override
    public ServletInputStream getInputStream()
    {
        return inputStream;
    }

    @Override
    public ServletOutputStream getOutputStream()
    {
        return outputStream;
    }
}
