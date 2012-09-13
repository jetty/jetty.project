//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.setuid;

import junit.framework.TestCase;
import org.eclipse.jetty.setuid.SetUID;
import java.io.File;
import org.eclipse.jetty.setuid.Passwd;
import org.eclipse.jetty.setuid.Group;

public class TestSetuid extends TestCase
{

    public void testSetuid() throws Exception
    {
             
    
        try
        {      
					// TODO use the dependency plugin to grab the proper lib and put it into place, no relative goop           
            File lib = new File("../../modules/native/target/libsetuid.so");
            String libPath = lib.getCanonicalPath();
            System.setProperty("jetty.libsetuid.path", libPath);   
            
            
            try
            {
                SetUID.getpwnam("TheQuickBrownFoxJumpsOverToTheLazyDog");
                assertTrue(false);
            }
            catch(SecurityException se)
            {
                assertTrue(true);
            }
            
            
            try
            {
                SetUID.getpwuid(-9999);
                assertTrue(false);
            }
            catch(SecurityException se)
            {
                assertTrue(true);
            }
            
            
            
            
            // get the passwd info of root
            Passwd passwd1 = SetUID.getpwnam("root");
            // get the roots passwd info using the aquired uid
            Passwd passwd2 = SetUID.getpwuid(passwd1.getPwUid());
            
            
            assertEquals(passwd1.getPwName(), passwd2.getPwName());
            assertEquals(passwd1.getPwPasswd(), passwd2.getPwPasswd());
            assertEquals(passwd1.getPwUid(), passwd2.getPwUid());
            assertEquals(passwd1.getPwGid(), passwd2.getPwGid());
            assertEquals(passwd1.getPwGecos(), passwd2.getPwGecos());
            assertEquals(passwd1.getPwDir(), passwd2.getPwDir());
            assertEquals(passwd1.getPwShell(), passwd2.getPwShell());
            
            
            try
            {
                SetUID.getgrnam("TheQuickBrownFoxJumpsOverToTheLazyDog");
                assertTrue(false);
            }
            catch(SecurityException se)
            {
                assertTrue(true);
            }
            
            
            try
            {
                SetUID.getgrgid(-9999);
                assertTrue(false);
            }
            catch(SecurityException se)
            {
                assertTrue(true);
            }
            
            
            
            
            // get the group using the roots groupid
            Group gr1 = SetUID.getgrgid(passwd1.getPwGid());
            // get the group name using the aquired name
            Group gr2 = SetUID.getgrnam(gr1.getGrName());
            
            assertEquals(gr1.getGrName(), gr2.getGrName());
            assertEquals(gr1.getGrPasswd(), gr2.getGrPasswd());
            assertEquals(gr1.getGrGid(), gr2.getGrGid());
            
            // search and check through membership lists
            if(gr1.getGrMem() != null)
            {
                assertEquals(gr1.getGrMem().length, gr2.getGrMem().length);
                for(int i=0; i<gr1.getGrMem().length; i++)
                {
                    assertEquals(gr1.getGrMem()[i], gr2.getGrMem()[i]);
                }
            }
            
            
        }
        catch(Throwable e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }    
    



}
