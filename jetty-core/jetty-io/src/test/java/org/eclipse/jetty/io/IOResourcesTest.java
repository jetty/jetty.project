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

package org.eclipse.jetty.io;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class IOResourcesTest
{
    @Test
    public void testToRetainableByteBuffer() throws Exception
    {
        Path resourcePath = MavenTestingUtils.getTestResourcePath("keystore.p12");
        Resource resource = ResourceFactory.root().newResource(resourcePath);
        RetainableByteBuffer retainableByteBuffer = IOResources.toRetainableByteBuffer(resource, new ByteBufferPool.NonPooling(), false);
        assertThat(retainableByteBuffer.remaining(), is((int)Files.size(resourcePath)));
    }
}
