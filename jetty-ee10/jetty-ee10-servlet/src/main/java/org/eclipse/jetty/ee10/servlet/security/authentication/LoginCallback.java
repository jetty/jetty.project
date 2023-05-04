//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet.security.authentication;

import java.security.Principal;
import javax.security.auth.Subject;

/**
 * This is similar to the jaspi PasswordValidationCallback but includes user
 * principal and group info as well.
 *
 * @version $Rev: 4792 $ $Date: 2009-03-18 22:55:52 +0100 (Wed, 18 Mar 2009) $
 */
public interface LoginCallback
{
    Subject getSubject();

    String getUserName();

    Object getCredential();

    boolean isSuccess();

    void setSuccess(boolean success);

    Principal getUserPrincipal();

    void setUserPrincipal(Principal userPrincipal);

    String[] getRoles();

    void setRoles(String[] roles);

    void clearPassword();
}
