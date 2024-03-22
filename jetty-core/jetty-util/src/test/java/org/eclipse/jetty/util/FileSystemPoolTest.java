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

package org.eclipse.jetty.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class FileSystemPoolTest
{
    public WorkDir workDir;

    @ParameterizedTest
    @ValueSource(strings = {"example.jar", "a/b/c/d/example.jar"})
    public void testCloseResourceFactoryAfterDeletingFileSystemBackingFile(String jarPathString) throws Exception
    {
        Path srcPath = MavenTestingUtils.getTestResourcePath("example.jar");
        Path rootPath = workDir.getEmptyPathDir();
        Path jarPath = rootPath.resolve(jarPathString);
        Files.createDirectories(jarPath.getParent());

        Files.copy(srcPath, jarPath, StandardCopyOption.REPLACE_EXISTING);
        ResourceFactory.Closeable rf1 = ResourceFactory.closeable();
        Resource resource1 = rf1.newResource(URIUtil.toJarFileUri(jarPath.toUri()));
        assertThat(resource1.exists(), is(true));
        // delete the jar file before closing the FS
        IO.delete(rootPath);
        rf1.close();
        assertThat(Files.exists(rootPath), is(false));

        // re-create the jar file at the exact same location
        Files.createDirectories(jarPath.getParent());
        Files.copy(srcPath, jarPath, StandardCopyOption.REPLACE_EXISTING);
        ResourceFactory.Closeable rf2 = ResourceFactory.closeable();
        Resource resource2 = rf2.newResource(URIUtil.toJarFileUri(jarPath.toUri()));
        assertThat(resource2.exists(), is(true));
        rf2.close();
    }
}
