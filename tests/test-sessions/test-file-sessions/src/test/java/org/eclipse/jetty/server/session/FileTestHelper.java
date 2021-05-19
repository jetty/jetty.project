//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileTestHelper
 */
public class FileTestHelper
{
    static int __workers = 0;

    public static File getFile(WorkDir workDir, String sessionId) throws IOException
    {
        try (Stream<Path> s = Files.list(workDir.getPath()))
        {
            return s
                .filter((path) -> path.getFileName().toString().endsWith("_" + sessionId))
                .findFirst()
                .map(Path::toFile)
                .orElse(null);
        }
    }

    public static void assertSessionExists(WorkDir workDir, String sessionId, boolean exists) throws IOException
    {
        try (Stream<Path> s = Files.list(workDir.getPath()))
        {
            Optional<Path> sessionPath = s
                .filter((path) -> path.getFileName().toString().contains(sessionId))
                .findFirst();
            if (exists)
                assertTrue(sessionPath.isPresent());
            else
                assertFalse(sessionPath.isPresent());
        }
    }

    public static void assertFileExists(WorkDir workDir, String filename, boolean exists)
    {
        Path path = workDir.getPath().resolve(filename);
        if (exists)
            assertTrue(Files.exists(path), "File should exist: " + path);
        else
            assertFalse(Files.exists(path), "File should NOT exist: " + path);
    }

    public static void createFile(WorkDir workDir, String filename)
        throws IOException
    {
        Path path = workDir.getPath().resolve(filename);
        Files.deleteIfExists(path);
        FS.touch(path);
    }

    public static void createFile(WorkDir workDir, String id, String contextPath, String vhost,
                                  String lastNode, long created, long accessed,
                                  long lastAccessed, long maxIdle, long expiry,
                                  long cookieSet, Map<String, Object> attributes)
        throws Exception
    {
        String filename = "" + expiry + "_" + contextPath + "_" + vhost + "_" + id;
        Path path = workDir.getPath().resolve(filename);
        try (OutputStream fos = Files.newOutputStream(path);
             DataOutputStream out = new DataOutputStream(fos))
        {
            out.writeUTF(id);
            out.writeUTF(contextPath);
            out.writeUTF(vhost);
            out.writeUTF(lastNode);
            out.writeLong(created);
            out.writeLong(accessed);
            out.writeLong(lastAccessed);
            out.writeLong(cookieSet);
            out.writeLong(expiry);
            out.writeLong(maxIdle);

            if (attributes != null)
            {
                SessionData tmp = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxIdle);
                ObjectOutputStream oos = new ObjectOutputStream(out);
                SessionData.serializeAttributes(tmp, oos);
            }
        }
    }

    public static boolean checkSessionPersisted(WorkDir workDir, SessionData data)
        throws Exception
    {
        String filename = "" + data.getExpiry() + "_" + data.getContextPath() + "_" + data.getVhost() + "_" + data.getId();
        Path file = workDir.getPath().resolve(filename);

        assertTrue(Files.exists(file));

        try (InputStream in = Files.newInputStream(file);
             DataInputStream di = new DataInputStream(in))
        {
            String id = di.readUTF();
            String contextPath = di.readUTF();
            String vhost = di.readUTF();
            String lastNode = di.readUTF();
            long created = di.readLong();
            long accessed = di.readLong();
            long lastAccessed = di.readLong();
            long cookieSet = di.readLong();
            long expiry = di.readLong();
            long maxIdle = di.readLong();

            assertEquals(data.getId(), id);
            assertEquals(data.getContextPath(), contextPath);
            assertEquals(data.getVhost(), vhost);
            assertEquals(data.getLastNode(), lastNode);
            assertEquals(data.getCreated(), created);
            assertEquals(data.getAccessed(), accessed);
            assertEquals(data.getLastAccessed(), lastAccessed);
            assertEquals(data.getCookieSet(), cookieSet);
            assertEquals(data.getExpiry(), expiry);
            assertEquals(data.getMaxInactiveMs(), maxIdle);

            SessionData tmp = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxIdle);
            ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(di);
            SessionData.deserializeAttributes(tmp, ois);

            //same number of attributes
            assertEquals(data.getAllAttributes().size(), tmp.getAllAttributes().size());
            //same keys
            assertEquals(tmp.getAllAttributes().keySet(), data.getKeys());
            //same values
            for (String name : data.getKeys())
            {
                assertEquals(tmp.getAttribute(name), data.getAttribute(name));
            }
        }

        return true;
    }

    public static void deleteFile(WorkDir workDir, String sessionId) throws IOException
    {
        // Collect
        try (Stream<Path> s = Files.list(workDir.getPath()))
        {
            s.filter((path) -> path.getFileName().toString().contains(sessionId))
                .collect(Collectors.toList()) // Delete outside of list stream
                .forEach(FS::deleteFile);
        }
    }

    public static FileSessionDataStoreFactory newSessionDataStoreFactory(WorkDir workDir)
    {
        FileSessionDataStoreFactory storeFactory = new FileSessionDataStoreFactory();
        storeFactory.setStoreDir(workDir.getPath().toFile());
        return storeFactory;
    }
}
