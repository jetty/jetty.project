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

package org.eclipse.jetty.ee10.servlets;

import java.io.FilterInputStream;
import java.io.InputStream;

/**
 * A simple pass-through input stream.
 * <p>
 * Used in some test cases where a proper resource open/close is needed for
 * some potentially optional layers of the input stream.
 */
public class PassThruInputStream extends FilterInputStream
{
    public PassThruInputStream(InputStream in)
    {
        super(in);
    }
}
