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
//        String siweMessage = "hello world";
//        SignedMessage signedMessage = credentials.signMessage(siweMessage);
//        String address = credentials.getAddress();
//        System.err.println(signedMessage);
//        System.err.println("address: " + credentials.getAddress());

        String address = "0x6ea456494436e225335e34dd9ffd53b98109afe3";
        System.err.println("address: " + address);
        SignedMessage signedMessage = new SignedMessage("hello world",
            "0x5e4659c34d3d672ef2840a63b7cca475b223d0cbf78eac1666964f6f16663f7d836bb3fda043173256867f6e9c29be1e401a03be52fd4df227e6d73320201f901b");

        String recoveredAddress = signedMessage.recoverAddress();
        System.err.println("recoveredAddress: " + recoveredAddress);
        assertThat(recoveredAddress, equalToIgnoringCase(address));
    }
}
