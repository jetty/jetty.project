//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.security.Credential;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class PropertyUserStoreTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    private File initUsersText() throws Exception
    {
        Path dir = testdir.getDir().toPath().toRealPath();
        FS.ensureDirExists(dir.toFile());
        File users = dir.resolve("users.txt").toFile();
        
        try (Writer writer = new BufferedWriter(new FileWriter(users)))
        {
            writer.append("tom: tom, roleA\n");
            writer.append("dick: dick, roleB\n");
            writer.append("harry: harry, roleA, roleB\n");
        }
        
        return users;
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
        final AtomicInteger userCount = new AtomicInteger();
        final File usersFile = initUsersText();

        PropertyUserStore store = new PropertyUserStore();
        store.setConfigPath(usersFile);

        store.registerUserListener(new PropertyUserStore.UserListener()
        {
            public void update(String username, Credential credential, String[] roleArray)
            {
                userCount.getAndIncrement();
            }

            public void remove(String username)
            {

            }
        });

        store.start();

        Assert.assertNotNull("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("tom"));
        Assert.assertNotNull("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("dick"));
        Assert.assertNotNull("Failed to retrieve UserIdentity directly from PropertyUserStore", store.getUserIdentity("harry"));
        Assert.assertEquals(3,userCount.get());
    }

    @Test
    public void testPropertyUserStoreLoadUpdateUser() throws Exception
    {
        final AtomicInteger userCount = new AtomicInteger();
        final List<String> users = new ArrayList<String>();
        final File usersFile = initUsersText();

        PropertyUserStore store = new PropertyUserStore();
        store.setHotReload(true);
        store.setConfigPath(usersFile);

        store.registerUserListener(new PropertyUserStore.UserListener()
        {
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

            }
        });

        store.start();
        
        Thread.sleep(2000);
        
        Assert.assertEquals(3,userCount.get());

        addAdditionalUser(usersFile,"skip: skip, roleA\n");

        long start = System.currentTimeMillis();
        while (userCount.get() < 4 && (System.currentTimeMillis() - start) < 10000)
        {
            Thread.sleep(10);
        }

        Assert.assertNotNull("Failed to retrieve UserIdentity from PropertyUserStore directly", store.getUserIdentity("skip"));
        Assert.assertEquals(4,userCount.get());

        Assert.assertTrue(users.contains("skip"));
    }

    @Test
    public void testPropertyUserStoreLoadRemoveUser() throws Exception
    {
        // initial user file (3) users
        final File usersFile = initUsersText();
        final AtomicInteger userCount = new AtomicInteger();
        final List<String> users = new ArrayList<String>();
        
        // adding 4th user
        addAdditionalUser(usersFile,"skip: skip, roleA\n");

        PropertyUserStore store = new PropertyUserStore();
        store.setHotReload(true);
        store.setConfigPath(usersFile);

        store.registerUserListener(new PropertyUserStore.UserListener()
        {
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
        });

        store.start();

        Thread.sleep(2000);

        Assert.assertEquals(4,userCount.get());

        // rewrite file with original 3 users
        initUsersText();
        Thread.sleep(3000);
        Assert.assertEquals(3,userCount.get());
    }
}
