// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging.impl;

import java.io.IOException;

/**
 * Appender for log content.
 */
public interface Appender
{
    void append(String date, int ms, Severity severity, String name, String message, Throwable t) throws IOException;

    void setProperty(String key, String value) throws Exception;

    void open() throws IOException;

    void close() throws IOException;
}
