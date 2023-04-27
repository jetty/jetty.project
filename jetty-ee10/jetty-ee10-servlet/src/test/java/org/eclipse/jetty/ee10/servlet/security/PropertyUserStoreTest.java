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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.security.Credential;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class PropertyUserStoreTest
{
    private static final class UserCount implements PropertyUserStore.UserListener
    {
        private final AtomicInteger userCount = new AtomicInteger();
        private final List<String> users = new ArrayList<>();

        private UserCount()
        {
        }

        @Override
        public void update(String username, Credential credential, String[] roleArray)
        {
            if (!users.contains(username))
            {
                users.add(username);
                userCount.getAndIncrement();
            }
        }

        @Override
        public void remove(String username)
        {
            users.remove(username);
            userCount.getAndDecrement();
        }

        public void awaitCount(int expectedCount) throws InterruptedException
        {
            long start = NanoTime.now();
            while (userCount.get() != expectedCount && NanoTime.secondsSince(start) < 10)
            {
                TimeUnit.MILLISECONDS.sleep(100);
            }

            assertThatCount(is(expectedCount));
        }

        public void assertThatCount(Matcher<Integer> matcher)
        {
            assertThat("User count", userCount.get(), matcher);
        }

        public void assertThatUsers(Matcher<Iterable<? super String>> matcher)
        {
            assertThat("Users list", users, matcher);
        }
    }

    public WorkDir workDir;
    public Path testdir;

    @BeforeEach
    public void beforeEach()
    {
        testdir = workDir.getEmptyPathDir();
    }

    private Path initUsersText() throws Exception
    {
        Path users = testdir.resolve("users.txt");
        Files.deleteIfExists(users);

        writeUser(users);
        return users;
    }

    private URI initUsersPackedFileText()
        throws Exception
    {
        Path users = testdir.resolve("users.txt");
        writeUser(users);
        Path usersJar = testdir.resolve("users.jar");
        String entryPath = "mountain_goat/pale_ale.txt";
        try (InputStream fileInputStream = Files.newInputStream(users))
        {
            try (OutputStream outputStream = Files.newOutputStream(usersJar))
            {
                try (JarOutputStream jarOutputStream = new JarOutputStream(outputStream))
                {
                    // add fake entry
                    jarOutputStream.putNextEntry(new JarEntry("foo/wine"));

                    JarEntry jarEntry = new JarEntry(entryPath);
                    jarOutputStream.putNextEntry(jarEntry);
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = fileInputStream.read(buffer)) != -1)
                    {
                        jarOutputStream.write(buffer, 0, bytesRead);
                    }
                    // add fake entry
                    jarOutputStream.putNextEntry(new JarEntry("foo/cheese"));
                }
            }
        }
        return URIUtil.uriJarPrefix(usersJar.toUri(), "!/" + entryPath);
    }

    private void writeUser(Path usersFile) throws IOException
    {
        try (Writer writer = Files.newBufferedWriter(usersFile, UTF_8))
        {
            writer.append("tom: tom, roleA\n");
            writer.append("dick: dick, roleB\n");
            writer.append("harry: harry, roleA, roleB\n");
        }
    }

    private void addAdditionalUser(Path usersFile, String userRef) throws Exception
    {
        Thread.sleep(1001);
        try (Writer writer = Files.newBufferedWriter(usersFile, UTF_8, StandardOpenOption.APPEND))
        {
            writer.append(userRef);
        }
    }

    @Test
    public void testPropertyUserStoreLoad() throws Exception
    {
        final UserCount userCount = new UserCount();
        final Path usersFile = initUsersText();

        PropertyUserStore store = new PropertyUserStore();
        store.setConfig(ResourceFactory.root().newResource(usersFile));

        store.registerUserListener(userCount);

        store.start();

        assertThat("Failed to retrieve user directly from PropertyUserStore", store.getUserPrincipal("tom"), notNullValue());
        assertThat("Failed to retrieve user directly from PropertyUserStore", store.getUserPrincipal("dick"), notNullValue());
        assertThat("Failed to retrieve user directly from PropertyUserStore", store.getUserPrincipal("harry"), notNullValue());
        userCount.assertThatCount(is(3));
        userCount.awaitCount(3);
    }

    @Test
    public void testPropertyUserStoreFails()
    {
        assertThrows(IllegalStateException.class, () ->
        {
            PropertyUserStore store = new PropertyUserStore();
            Resource doesNotExist = ResourceFactory.root().newResource("file:///this/file/does/not/exist.txt");
            store.setConfig(doesNotExist);
            store.start();
        });
    }

    @Test
    public void testPropertyUserStoreLoadFromJarFile() throws Exception
    {
        final UserCount userCount = new UserCount();
        final URI usersFile = initUsersPackedFileText();

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource jarResource = resourceFactory.newResource(usersFile);
            PropertyUserStore store = new PropertyUserStore();
            store.setConfig(jarResource);

            store.registerUserListener(userCount);

            store.start();

            assertThat("Failed to retrieve user directly from PropertyUserStore", //
                store.getUserPrincipal("tom"), notNullValue());
            assertThat("Failed to retrieve user directly from PropertyUserStore", //
                store.getUserPrincipal("dick"), notNullValue());
            assertThat("Failed to retrieve user directly from PropertyUserStore", //
                store.getUserPrincipal("harry"), notNullValue());
            userCount.assertThatCount(is(3));
            userCount.awaitCount(3);
        }
    }

    @Test
    public void testPropertyUserStoreLoadUpdateUser() throws Exception
    {
        final UserCount userCount = new UserCount();
        final Path usersFile = initUsersText();
        final AtomicInteger loadCount = new AtomicInteger(0);
        PropertyUserStore store = new PropertyUserStore()
        {
            @Override
            protected void loadUsers() throws IOException
            {
                loadCount.incrementAndGet();
                super.loadUsers();
            }
        };
        store.setRefreshInterval(1);
        store.setConfig(ResourceFactory.root().newResource(usersFile));
        store.registerUserListener(userCount);

        store.start();

        userCount.assertThatCount(is(3));
        assertThat(loadCount.get(), is(1));

        addAdditionalUser(usersFile, "skip: skip, roleA\n");
        userCount.awaitCount(4);
        assertThat(loadCount.get(), is(2));
        assertThat(store.getUserPrincipal("skip"), notNullValue());
        userCount.assertThatCount(is(4));
        userCount.assertThatUsers(hasItem("skip"));

        if (OS.LINUX.isCurrentOs())
            Files.createFile(testdir.toRealPath().resolve("unrelated.txt"),
                PosixFilePermissions.asFileAttribute(EnumSet.noneOf(PosixFilePermission.class)));
        else
            Files.createFile(testdir.toRealPath().resolve("unrelated.txt"));

        Scanner scanner = store.getBean(Scanner.class);
        CountDownLatch latch = new CountDownLatch(2);
        scanner.scan(Callback.from(latch::countDown));
        scanner.scan(Callback.from(latch::countDown));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(loadCount.get(), is(2));

        userCount.assertThatCount(is(4));
        userCount.assertThatUsers(hasItem("skip"));
    }

    @Test
    public void testPropertyUserStoreLoadRemoveUser() throws Exception
    {
        final UserCount userCount = new UserCount();
        // initial user file (3) users
        final Path usersFile = initUsersText();

        // adding 4th user
        addAdditionalUser(usersFile, "skip: skip, roleA\n");

        PropertyUserStore store = new PropertyUserStore();
        store.setHotReload(true);
        store.setConfig(ResourceFactory.root().newResource(usersFile));

        store.registerUserListener(userCount);

        store.start();

        userCount.assertThatCount(is(4));

        // rewrite file with original 3 users
        initUsersText();

        userCount.awaitCount(3);
    }
}
