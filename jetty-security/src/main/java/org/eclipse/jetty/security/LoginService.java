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
 * @version $Rev: 4734 $ $Date: 2009-03-07 18:46:18 +0100 (Sat, 07 Mar 2009) $
 */
public interface LoginService
{
    String getName();
    UserIdentity login(String username,Object credentials);
    
    IdentityService<UserIdentity,?> getIdentityService();
    void setIdentityService(IdentityService<UserIdentity,?> service);
}
