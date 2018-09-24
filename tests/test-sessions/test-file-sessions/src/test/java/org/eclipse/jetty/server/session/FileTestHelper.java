//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.IO;

/**
 * FileTestHelper
 * 
 */
public class FileTestHelper
{
    static int __workers=0;
    static File _tmpDir;
    
    public  static void setup ()
    throws Exception
    {
        
        _tmpDir = File.createTempFile("file", null);
        _tmpDir.delete();
        _tmpDir.mkdirs();
        _tmpDir.deleteOnExit();
    }
    
    
    public static void teardown ()
    {
        IO.delete(_tmpDir);
        _tmpDir = null;
    }
    
    
    public static void assertStoreDirEmpty (boolean isEmpty)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        if (isEmpty)
        {
            if (files != null)
                assertEquals(0, files.length);
        }
        else
        {
            assertNotNull(files);
            assertFalse(files.length==0);
        }
    }

    
    public static File getFile (String sessionId)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        String fname = null;
        for (String name:files)
        {
            if (name.contains(sessionId))
            {
                fname=name;
                break;
            }
        }
        
        if (fname != null)
            return new File (_tmpDir, fname);
        return null;
    }

    public static void assertSessionExists (String sessionId, boolean exists)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        if (exists)
            assertFalse(files.length == 0);
        boolean found = false;
        for (String name:files)
        {
            if (name.contains(sessionId))
            {
                found = true;
                break;
            }
        }
        if (exists)
            assertTrue(found);
        else
            assertFalse(found);
    }
    
    public static void assertFileExists (String filename, boolean exists)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        File file = new File (_tmpDir, filename);
        if (exists)
            assertTrue(file.exists());
        else
            assertFalse(file.exists());
    }
    
    
    public static void createFile (String filename)
    throws IOException
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        
        File file = new File (_tmpDir, filename);
        Files.deleteIfExists(file.toPath());
        file.createNewFile();
    }
    
    

    public static void createFile (String id, String contextPath, String vhost, 
                                   String lastNode, long created, long accessed, 
                                   long lastAccessed, long maxIdle, long expiry,
                                   long cookieSet, Map<String,Object> attributes)
    throws Exception
    {
        String filename = ""+expiry+"_"+contextPath+"_"+vhost+"_"+id;
        File file = new File(_tmpDir, filename);
        try(FileOutputStream fos = new FileOutputStream(file,false))
        {
            DataOutputStream out = new DataOutputStream(fos);
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
                List<String> keys = new ArrayList<String>(attributes.keySet());
                out.writeInt(keys.size());
                ObjectOutputStream oos = new ObjectOutputStream(out);
                for (String name:keys)
                {
                    oos.writeUTF(name);
                    oos.writeObject(attributes.get(name));
                }
            }
        }
    }
    
    
    public static boolean checkSessionPersisted (SessionData data)
    throws Exception
    {
        String filename = ""+data.getExpiry()+"_"+data.getContextPath()+"_"+data.getVhost()+"_"+data.getId();
        File file = new File(_tmpDir, filename);
        assertTrue(file.exists());
        
        try (FileInputStream in = new FileInputStream(file))
        {
            DataInputStream di = new DataInputStream(in);

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

            Map<String,Object> attributes = new HashMap<>();
            
            int size = di.readInt();
            if (size > 0)
            {
               ClassLoadingObjectInputStream ois =  new ClassLoadingObjectInputStream(di);
                for (int i=0; i<size;i++)
                {
                    String key = ois.readUTF();
                    Object value = ois.readObject();
                    attributes.put(key,value);
                }
            }
            
            //same number of attributes
            assertEquals(data.getAllAttributes().size(), attributes.size());
            //same keys
            assertTrue(data.getKeys().equals(attributes.keySet()));
            //same values
            for (String name:data.getKeys())
            {
                assertTrue(data.getAttribute(name).equals(attributes.get(name)));
            }
        }
        
        return true;
    }
    
    public static void deleteFile (String sessionId)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        assertFalse(files.length == 0);
        String filename = null;
        for (String name:files)
        {
            if (name.contains(sessionId))
            {
                filename = name;
                break;
            }
        }
        if (filename != null)
        {
            File f = new File (_tmpDir, filename);
            assertTrue(f.delete());
        }
    }
 
    public static FileSessionDataStoreFactory newSessionDataStoreFactory()
    {
        FileSessionDataStoreFactory storeFactory = new FileSessionDataStoreFactory();
        storeFactory.setStoreDir(_tmpDir);
        return storeFactory;
    }
}
