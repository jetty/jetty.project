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

package org.eclipse.jetty.util.resource;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryResourceTest
{
    @Test
    public void testJettyLogging() throws Exception
    {
        Resource resource = new MemoryResource(Loader.getResource("jetty-logging.properties"));
        assertTrue(resource.exists());
        String contents = IO.toString(resource.newInputStream());
        assertThat(contents, startsWith("#org.eclipse.jetty.util.LEVEL=DEBUG"));
    }
}
