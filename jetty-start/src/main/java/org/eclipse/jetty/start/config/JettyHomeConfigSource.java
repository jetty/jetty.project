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

package org.eclipse.jetty.start.config;

import java.io.IOException;
import java.nio.file.Path;

/**
 * ${jetty.home} specific ConfigSource
 */
public class JettyHomeConfigSource extends DirConfigSource
{
    // Standard weight for ${jetty.home}, so that it comes after everything else
    private static final int WEIGHT = 9999999;

    public JettyHomeConfigSource(Path dir) throws IOException
    {
        super("${jetty.home}", dir, WEIGHT, false);
    }
}
