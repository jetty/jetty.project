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

package org.eclipse.jetty.server;

import java.io.IOException;

/**
 * TODO
 * @see Server#setRequestLog(RequestLog)
 */
public interface RequestLog
{
    /**
     * @param request The request to log.
     * @param response The response to log.
     */
    void log(Request request, Response response);

    /**
     * Writes the generated log string to a log sink
     */
    interface Writer
    {
        void write(String requestEntry) throws IOException;
    }

    class Collection implements RequestLog
    {
        private final RequestLog[] _logs;

        public Collection(RequestLog... logs)
        {
            _logs = logs;
        }

        @Override
        public void log(Request request, Response response)
        {
            for (RequestLog log : _logs)
            {
                log.log(request, response);
            }
        }
    }
}
