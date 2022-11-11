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

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ResourceFactoryTest
{
    @Test
    public void testCustomUriScheme()
    {
        ResourceFactory.addResourceFactory("custom", new CustomResourceFactory());
        Resource resource = ResourceFactory.root().newResource("custom://foo");
        assertThat(resource.getURI(), is(URI.create("custom://foo")));
        assertThat(resource.getName(), is("custom-impl"));
    }

    public static class CustomResourceFactory implements ResourceFactory
    {
        @Override
        public Resource newResource(URI uri)
        {
            return new Resource()
            {
                @Override
                public Path getPath()
                {
                    return null;
                }

                @Override
                public boolean isContainedIn(Resource r)
                {
                    return false;
                }

                @Override
                public boolean isDirectory()
                {
                    return false;
                }

                @Override
                public boolean isReadable()
                {
                    return false;
                }

                @Override
                public URI getURI()
                {
                    return uri;
                }

                @Override
                public String getName()
                {
                    return "custom-impl";
                }

                @Override
                public String getFileName()
                {
                    return null;
                }

                @Override
                public Resource resolve(String subUriPath)
                {
                    return null;
                }
            };
        }
    }
}
