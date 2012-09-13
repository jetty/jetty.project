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

/**
 * Class is the equivalent java class used for holding values from native c code structure group. for more information please see man pages for getgrnam and getgrgid
 * struct group {
 *             char   *gr_name;        // group name 
 *             char   *gr_passwd;     // group password
 *             gid_t   gr_gid;          // group ID 
 *             char  **gr_mem;        //  group members 
 *         };
 *
 */

public class Group
{
    private String _grName; /* group name */
    private String _grPasswd; /* group password */
    private int _grGid; /* group id */
    private String[] _grMem; /* group members */
    
    

    public String getGrName()
    {
        return _grName;
    }
    
    public void setGrName(String grName)
    {
        _grName = grName;
    }    

    public String getGrPasswd()
    {
        return _grPasswd;
    }
    
    public void setGrPasswd(String grPasswd)
    {
        _grPasswd = grPasswd;
    }

    public int getGrGid()
    {
        return _grGid;
    }
    
    public void setGrGid(int grGid)
    {
        _grGid = grGid;
    }
    
    public String[] getGrMem()
    {
        return _grMem;
    }
    
    public void setGrMem(String[] grMem)
    {
        _grMem = grMem;
    }
    
}
