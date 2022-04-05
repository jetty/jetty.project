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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.security.Credential;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.condition.OS.MAC;

@ExtendWith(WorkDirExtension.class)
public class PropertyUserStoreTest
{
    private final class UserCount implements PropertyUserStore.UserListener
    {
        private final AtomicInteger userCount = new AtomicInteger();
        private final List<String> users = new ArrayList<String>();

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
            long timeout = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + TimeUnit.SECONDS.toMillis(10);

            while (userCount.get() != expectedCount && (TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) < timeout))
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

    public WorkDir testdir;

    private Path initUsersText() throws Exception
    {
        Path dir = testdir.getPath();
        Path users = dir.resolve("users.txt");
        Files.deleteIfExists(users);

        writeUser(users);
        return users;
    }

    private String initUsersPackedFileText()
        throws Exception
    {
        Path dir = testdir.getPath();
        File users = dir.resolve("users.txt").toFile();
        writeUser(users);
        File usersJar = dir.resolve("users.jar").toFile();
        String entryPath = "mountain_goat/pale_ale.txt";
        try (FileInputStream fileInputStream = new FileInputStream(users))
        {
            try (OutputStream outputStream = new FileOutputStream(usersJar))
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
        return "jar:" + usersJar.toURI().toASCIIString() + "!/" + entryPath;
    }

    private void writeUser(File usersFile) throws IOException
    {
        writeUser(usersFile.toPath());
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
        testdir.ensureEmpty();

        final UserCount userCount = new UserCount();
        final Path usersFile = initUsersText();

        PropertyUserStore store = new PropertyUserStore();
        store.setConfigFile(usersFile.toFile());

        store.registerUserListener(userCount);

        store.start();

        assertThat("Failed to retrieve user directly from PropertyUserStore", store.getUserPrincipal("tom"), notNullValue());
        assertThat("Failed to retrieve user directly from PropertyUserStore", store.getUserPrincipal("dick"), notNullValue());
        assertThat("Failed to retrieve user directly from PropertyUserStore", store.getUserPrincipal("harry"), notNullValue());
        userCount.assertThatCount(is(3));
        userCount.awaitCount(3);
    }

    @Test
    public void testPropertyUserStoreFails() throws Exception
    {
        assertThrows(IllegalStateException.class, () ->
        {
            PropertyUserStore store = new PropertyUserStore();
            store.setConfig("file:/this/file/does/not/exist.txt");
            store.start();
        });
    }

    @Test
    public void testPropertyUserStoreLoadFromJarFile() throws Exception
    {
        testdir.ensureEmpty();

        final UserCount userCount = new UserCount();
        final String usersFile = initUsersPackedFileText();

        PropertyUserStore store = new PropertyUserStore();
        store.setConfig(usersFile);

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

    @Test
    @DisabledOnOs(MAC)
    public void testPropertyUserStoreLoadUpdateUser() throws Exception
    {
        testdir.ensureEmpty();

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
        store.setHotReload(true);
        store.setConfigFile(usersFile.toFile());
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
            Files.createFile(testdir.getPath().toRealPath().resolve("unrelated.txt"),
                PosixFilePermissions.asFileAttribute(EnumSet.noneOf(PosixFilePermission.class)));
        else
            Files.createFile(testdir.getPath().toRealPath().resolve("unrelated.txt"));

        Thread.sleep(1100);
        assertThat(loadCount.get(), is(2));

        userCount.assertThatCount(is(4));
        userCount.assertThatUsers(hasItem("skip"));
    }

    @Test
    public void testPropertyUserStoreLoadRemoveUser() throws Exception
    {
        testdir.ensureEmpty();

        final UserCount userCount = new UserCount();
        // initial user file (3) users
        final Path usersFile = initUsersText();

        // adding 4th user
        addAdditionalUser(usersFile, "skip: skip, roleA\n");

        PropertyUserStore store = new PropertyUserStore();
        store.setHotReload(true);
        store.setConfigFile(usersFile.toFile());

        store.registerUserListener(userCount);

        store.start();

        userCount.assertThatCount(is(4));

        // rewrite file with original 3 users
        initUsersText();

        userCount.awaitCount(3);
    }
}
