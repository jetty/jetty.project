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

package org.eclipse.jetty.tests.distribution.jettysh;

import java.util.function.Consumer;

import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder;

/**
 * Simplify the use of {@link ImageFromDockerfile} to get some sanity in naming convention
 * and {@link #toString()} behaviors so that Test execution makes sense.
 */
public class ImageFromDSL extends ImageFromDockerfile
{
    private ImageFromDSL parentImage;

    public ImageFromDSL(ImageFromDSL baseImage, String suffix, Consumer<DockerfileBuilder> builderConsumer)
    {
        this(baseImage.getDockerImageName() + "-" + suffix, builderConsumer);
        this.parentImage = baseImage;
    }

    public ImageFromDSL(String name, Consumer<DockerfileBuilder> builderConsumer)
    {
        super(name, false);
        withDockerfileFromBuilder(builderConsumer);
    }

    public ImageFromDSL getParentImage()
    {
        return parentImage;
    }

    @Override
    public String toString()
    {
        return getDockerImageName();
    }
}
