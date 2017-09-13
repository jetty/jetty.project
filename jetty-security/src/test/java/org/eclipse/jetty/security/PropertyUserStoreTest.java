//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.security.Credential;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilePermission;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

public class PropertyUserStoreTest
{
    private final class UserCount implements PropertyUserStore.UserListener
    {
        private final AtomicInteger userCount = new AtomicInteger();
        private final List<String> users = new ArrayList<String>();

        private UserCount()
        {
        }

        public void update(String username, Credential credential, String[] roleArray)
        {
            if (!users.contains(username))
            {
                users.add(username);
                userCount.getAndIncrement();
            }
        }

        public void remove(String username)
        {
            users.remove(username);
            userCount.getAndDecrement();
        }

        public void awaitCount(int expectedCount) throws InterruptedException
        {
            long timeout = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
            
            while (userCount.get() != expectedCount && (System.currentTimeMillis() < timeout))
            {
                TimeUnit.MILLISECONDS.sleep(100);
            }
            
            assertThatCount(is(expectedCount));
        }

        public void assertThatCount(Matcher<Integer> matcher)
        {
            assertThat("User count",userCount.get(),matcher);
        }

        public void assertThatUsers(Matcher<Iterable<? super String>> matcher)
        {
            assertThat("Users list",users,matcher);
        }
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    private File initUsersText() throws Exception
    {
        Path dir = testdir.getPath().toRealPath();
        FS.ensureDirExists(dir.toFile());
        File users = dir.resolve("users.txt").toFile();

        writeUser( users );
        return users;
    }

    private String initUsersPackedFileText()
        throws Exception
    {
        Path dir = testdir.getPath().toRealPath();
        FS.ensureDirExists( dir.toFile() );
        File users = dir.resolve( "users.txt" ).toFile();
        writeUser( users );
        File usersJar = dir.resolve( "users.jar" ).toFile();
        String entryPath = "mountain_goat/pale_ale.txt";
        try (FileInputStream fileInputStream = new FileInputStream( users ))
        {
            try (OutputStream outputStream = new FileOutputStream( usersJar ))
            {
                try (JarOutputStream jarOutputStream = new JarOutputStream( outputStream ))
                {
                    // add fake entry
                    jarOutputStream.putNextEntry( new JarEntry( "foo/wine" ) );

                    JarEntry jarEntry = new JarEntry( entryPath );
                    jarOutputStream.putNextEntry( jarEntry );
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ( ( bytesRead = fileInputStream.read( buffer ) ) != -1 )
                    {
                        jarOutputStream.write( buffer, 0, bytesRead );
                    }
                    // add fake entry
                    jarOutputStream.putNextEntry( new JarEntry( "foo/cheese" ) );
                }
            }

        }
        return "jar:" + usersJar.toURI().toASCIIString() + "!/" + entryPath;
    }

    private void writeUser(File usersFile)
        throws Exception
    {
        try (Writer writer = new BufferedWriter(new FileWriter(usersFile)))
        {
            writer.append("tom: tom, roleA\n");
            writer.append("dick: dick, roleB\n");
            writer.append("harry: harry, roleA, roleB\n");
        }
    }

    private void addAdditionalUser(File usersFile, String userRef) throws Exception
    {
        Thread.sleep(1001);
        try (Writer writer = new BufferedWriter(new FileWriter(usersFile,true)))
        {
            writer.append(userRef);
        }
    }

    @Test
    public void testPropertyUserStoreLoad() throws Exception
    {
        final UserCount userCount = new UserCount();
        final File usersFile = initUsersText();

        PropertyUserStore store = new PropertyUserStore();
        store.setConfigFile(usersFile);

        store.registerUserListener(userCount);

        store.start();

        assertThat("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("tom"), notNullValue());
        assertThat("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("dick"), notNullValue());
        assertThat("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("harry"), notNullValue());
        userCount.assertThatCount(is(3));
        userCount.awaitCount(3);
    }

    @Test
    public void testPropertyUserStoreLoadFromJarFile() throws Exception
    {
        final UserCount userCount = new UserCount();
        final String usersFile = initUsersPackedFileText();

        PropertyUserStore store = new PropertyUserStore();
        store.setConfig(usersFile);

        store.registerUserListener(userCount);

        store.start();

        assertThat("Failed to retrieve UserIdentity directly from PropertyUserStore", //
                   store.getUserIdentity("tom"), notNullValue());
        assertThat("Failed to retrieve UserIdentity directly from PropertyUserStore", //
                   store.getUserIdentity("dick"), notNullValue());
        assertThat("Failed to retrieve UserIdentity directly from PropertyUserStore", //
                   store.getUserIdentity("harry"), notNullValue());
        userCount.assertThatCount(is(3));
        userCount.awaitCount(3);
    }

    @Test
    public void testPropertyUserStoreLoadUpdateUser() throws Exception
    {
        assumeThat("Skipping on OSX", OS.IS_OSX, is(false));
        final UserCount userCount = new UserCount();
        final File usersFile = initUsersText();
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
        store.setConfigFile(usersFile);
        store.registerUserListener(userCount);

        store.start();
        
        userCount.assertThatCount(is(3));
        assertThat(loadCount.get(),is(1));
        
        addAdditionalUser(usersFile,"skip: skip, roleA\n");
        userCount.awaitCount(4);
        assertThat(loadCount.get(),is(2));
        assertThat(store.getUserIdentity("skip"), notNullValue());
        userCount.assertThatCount(is(4));
        userCount.assertThatUsers(hasItem("skip"));
        
        if (OS.IS_LINUX)
            Files.createFile(testdir.getPath().toRealPath().resolve("unrelated.txt"),
                PosixFilePermissions.asFileAttribute(EnumSet.noneOf(PosixFilePermission.class)));
        else
            Files.createFile(testdir.getPath().toRealPath().resolve("unrelated.txt"));
        
        Thread.sleep(1100);
        assertThat(loadCount.get(),is(2));

        userCount.assertThatCount(is(4));
        userCount.assertThatUsers(hasItem("skip"));
    }

    @Test
    public void testPropertyUserStoreLoadRemoveUser() throws Exception
    {
        assumeThat("Skipping on OSX", OS.IS_OSX, is(false));
        final UserCount userCount = new UserCount();
        // initial user file (3) users
        final File usersFile = initUsersText();
        
        // adding 4th user
        addAdditionalUser(usersFile,"skip: skip, roleA\n");

        PropertyUserStore store = new PropertyUserStore();
        store.setHotReload(true);
        store.setConfigFile(usersFile);

        store.registerUserListener(userCount);

        store.start();

        userCount.assertThatCount(is(4));

        // rewrite file with original 3 users
        initUsersText();
        
        userCount.awaitCount(3);
    }
}
