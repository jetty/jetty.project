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
        // These contain jetty-home, so remove them when the tests are done
        this(baseImage.getDockerImageName() + "-" + suffix, true, builderConsumer);
        this.parentImage = baseImage;
    }

    public ImageFromDSL(String name, Consumer<DockerfileBuilder> builderConsumer)
    {
        // OS images never change, keep them around so docker can reuse them
        this(name, false, builderConsumer);
    }

    private ImageFromDSL(String name, boolean deleteOnExit, Consumer<DockerfileBuilder> builderConsumer)
    {
        super(name, deleteOnExit);
        withDockerfileFromBuilder(builderConsumer);
        // clean up build containers even if the build fails
        withBuildImageCmdModifier(cmd -> cmd.withForcerm(true));
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
