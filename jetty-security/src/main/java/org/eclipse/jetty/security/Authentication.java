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
    public enum Status
    {
        SEND_FAILURE(false), SEND_SUCCESS(true), SEND_CONTINUE(false), SUCCESS(true);
        boolean _success;
        Status(boolean success) {_success=success; }
        public boolean isSuccess(){ return _success;}
    }
    
    Status getAuthStatus();

    String getAuthMethod();
    
    UserIdentity getUserIdentity();
    
    boolean isSuccess();
    
    
    public static final Authentication SUCCESS_UNAUTH_RESULTS = new Authentication()
    {
        public String getAuthMethod() {return null;}
        public Status getAuthStatus() {return Authentication.Status.SUCCESS;}
        public UserIdentity getUserIdentity() {return UserIdentity.UNAUTHENTICATED_IDENTITY;}
        public boolean isSuccess() {return true;}
    };
    
    public static final Authentication SEND_CONTINUE_RESULTS = new Authentication()
    {
        public String getAuthMethod() {return null;}
        public Status getAuthStatus() {return Authentication.Status.SEND_CONTINUE;}
        public UserIdentity getUserIdentity() {return UserIdentity.UNAUTHENTICATED_IDENTITY;}
        public boolean isSuccess() {return false;}
    };
    
    public static final Authentication SEND_FAILURE_RESULTS = new Authentication()
    {
        public String getAuthMethod() {return null;}
        public Status getAuthStatus() {return Authentication.Status.SEND_FAILURE;}
        public UserIdentity getUserIdentity() {return UserIdentity.UNAUTHENTICATED_IDENTITY;}
        public boolean isSuccess() {return false;}
    };
    
}
