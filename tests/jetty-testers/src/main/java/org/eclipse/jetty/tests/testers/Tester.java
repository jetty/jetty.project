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

package org.eclipse.jetty.tests.testers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public interface Tester
{
    /**
     * @return a free port chosen by the OS that can be used to listen to
     * @throws IOException if a free port is not available
     */
    static int freePort() throws IOException
    {
        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("localhost", 0));
            return server.getLocalPort();
        }
    }

    /**
     * <p>Returns a Java executable under the {@code $JAVA_HOME/bin} directory.</p>
     *
     * @param command the Java command to search for (for example, "java", "javac", "jstack", etc.)
     * @return the Java command
     */
    static String getJavaExecutable(String command)
    {
        String[] javaExecutables = new String[]{command, command + ".exe"};
        Path javaBinDir = Paths.get(System.getProperty("java.home")).resolve("bin");
        for (String javaExecutable : javaExecutables)
        {
            Path javaFile = javaBinDir.resolve(javaExecutable);
            if (Files.exists(javaFile) && Files.isRegularFile(javaFile))
                return javaFile.toAbsolutePath().toString();
        }
        return command;
    }
}
