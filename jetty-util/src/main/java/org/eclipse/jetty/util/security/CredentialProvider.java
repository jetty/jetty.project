//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.security;

/**
 * Provider of credentials, it converts a String into a credential if it starts with a given prefix
 */
public interface CredentialProvider
{
    /**
     * Get a credential from a String
     *
     * @param credential String representation of the credential
     * @return A Credential or Password instance.
     */
    Credential getCredential(String credential);

    /**
     * Get the prefix of the credential strings convertible into credentials
     *
     * @return prefix of the credential strings convertible into credentials
     */
    String getPrefix();
}
