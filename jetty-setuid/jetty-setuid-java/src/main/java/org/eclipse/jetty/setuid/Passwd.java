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
 * Class is the equivalent java class used for holding values from native c code structure passwd. for more information please see man pages for getpwuid and getpwnam
 *   struct passwd {
 *        char    *pw_name;      // user name
 *        char    *pw_passwd;   // user password
 *        uid_t   pw_uid;         // user id
 *        gid_t   pw_gid;         // group id
 *        char    *pw_gecos;     // real name
 *        char    *pw_dir;        // home directory
 *       char    *pw_shell;      // shell program
 *    };
 *
 */

public class Passwd
{
    private String _pwName; /* user name */
    private String _pwPasswd; /* user password */
    private int _pwUid; /* user id */
    private int _pwGid; /* group id */
    private String _pwGecos; /* real name */
    private String _pwDir; /* home directory */
    private String _pwShell; /* shell program */
    

    public String getPwName()
    {
        return _pwName;
    }
    
    public void setPwName(String pwName)
    {
        _pwName = pwName;
    }    

    public String getPwPasswd()
    {
        return _pwPasswd;
    }
    
    public void setPwPasswd(String pwPasswd)
    {
        _pwPasswd = pwPasswd;
    }

    public int getPwUid()
    {
        return _pwUid;
    }
    
    public void setPwUid(int pwUid)
    {
        _pwUid = pwUid;
    }

    public int getPwGid()
    {
        return _pwGid;
    }
    
    public void setPwGid(int pwGid)
    {
        _pwGid = pwGid;
    }
    
    public String getPwGecos()
    {
        return _pwGecos;
    }
    
    public void setPwGid(String pwGecos)
    {
        _pwGecos = pwGecos;
    }
    
    public String getPwDir()
    {
        return _pwDir;
    }
    
    public void setPwDir(String pwDir)
    {
        _pwDir = pwDir;
    }
    
    public String getPwShell()
    {
        return _pwShell;
    }
    
    public void setPwShell(String pwShell)
    {
        _pwShell = pwShell;
    }
    
}
