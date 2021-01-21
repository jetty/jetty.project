//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler.gzip;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.compression.DeflaterPool;

public interface GzipFactory
{
    DeflaterPool.Entry getDeflaterEntry(Request request, long contentLength);

    boolean isMimeTypeGzipable(String mimetype);
}
