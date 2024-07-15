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

package org.eclipse.jetty.security.siwe;

import org.eclipse.jetty.security.siwe.util.EthereumCredentials;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;

public class SignatureVerificationTest
{
    private final EthereumCredentials credentials = new EthereumCredentials();

    @Test
    public void testSignatureVerification() throws Exception
    {
        String siweMessage = "hello world";
        SignedMessage signedMessage = credentials.signMessage(siweMessage);
        String address = credentials.getAddress();
        String recoveredAddress = signedMessage.recoverAddress();
        assertThat(recoveredAddress, equalToIgnoringCase(address));
    }
}
