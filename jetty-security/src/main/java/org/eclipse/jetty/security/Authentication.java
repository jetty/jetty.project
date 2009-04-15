// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security;

import org.eclipse.jetty.server.UserIdentity;

/**
 * Authentication state of a user.
 * 
 * @version $Rev: 4701 $ $Date: 2009-03-03 13:01:26 +0100 (Tue, 03 Mar 2009) $
 */
public interface Authentication
{    
    String getAuthMethod();
    
    UserIdentity getUserIdentity();
    
    boolean isSuccess();
    
    boolean isSend();
    
    void logout();

    
    public static final Authentication FAILED = new Authentication()
    {
        public String getAuthMethod()
        {
            return null;
        }

        public UserIdentity getUserIdentity()
        {
            return UserIdentity.UNAUTHENTICATED_IDENTITY;
        }

        public boolean isSuccess()
        {
            return false;
        }

        public boolean isSend()
        {
            return true;
        }

        public void logout()
        {
        }
        
    };
    
    public static final Authentication CHALLENGE = new Authentication()
    {
        public String getAuthMethod()
        {
            return null;
        }

        public UserIdentity getUserIdentity()
        {
            return UserIdentity.UNAUTHENTICATED_IDENTITY;
        }

        public boolean isSuccess()
        {
            return false;
        }

        public boolean isSend()
        {
            return true;
        }

        public void logout()
        {
        }
        
    };
    
    public static final Authentication NOT_CHECKED = new Authentication()
    {
        public String getAuthMethod()
        {
            return null;
        }

        public UserIdentity getUserIdentity()
        {
            return UserIdentity.UNAUTHENTICATED_IDENTITY;
        }

        public boolean isSuccess()
        {
            return false;
        }

        public boolean isSend()
        {
            return false;
        }

        public void logout()
        {
        }
        
    };
    
}
