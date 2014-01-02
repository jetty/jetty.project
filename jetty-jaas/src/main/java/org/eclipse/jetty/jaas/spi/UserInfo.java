//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.jaas.spi;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.security.Credential;

/**
 * UserInfo
 *
 * This is the information read from the external source
 * about a user.
 * 
 * Can be cached by a UserInfoCache implementation
 */
public class UserInfo
{
    
    private String _userName;
    private Credential _credential;
    private List<String> _roleNames;
    
    
    public UserInfo (String userName, Credential credential, List<String> roleNames)
    {
        _userName = userName;
        _credential = credential;
        _roleNames = new ArrayList<String>();
        if (roleNames != null)
        {
            _roleNames.addAll(roleNames);
        }
    }
    
    public String getUserName()
    {
        return this._userName;
    }
    
    public List<String> getRoleNames ()
    {
        return new ArrayList<String>(_roleNames);
    }
    
    public boolean checkCredential (Object suppliedCredential)
    {
        return _credential.check(suppliedCredential);
    }
    
    protected Credential getCredential ()
    {
        return _credential;
    }
    
}
