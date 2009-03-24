// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.plus.jaas.spi;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.security.Credential;

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
    
    private String userName;
    private Credential credential;
    private List roleNames;
    
    
    public UserInfo (String userName, Credential credential, List roleNames)
    {
        this.userName = userName;
        this.credential = credential;
        this.roleNames = new ArrayList();
        if (roleNames != null)
            this.roleNames.addAll(roleNames);
    }
    
    public String getUserName()
    {
        return this.userName;
    }
    
    public List getRoleNames ()
    {
        return new ArrayList(this.roleNames);
    }
    
    public boolean checkCredential (Object suppliedCredential)
    {
        return this.credential.check(suppliedCredential);
    }
    
    protected Credential getCredential ()
    {
        return this.credential;
    }
    
}
