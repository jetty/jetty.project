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

package org.eclipse.jetty.tests.distribution.siwe;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.security.siwe.EthereumAuthenticator;
import org.eclipse.jetty.security.siwe.internal.EthereumUtil;

import static org.eclipse.jetty.security.siwe.internal.EthereumUtil.keccak256;

public class EthereumCredentials
{
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String address;
    private final BouncyCastleProvider provider = new BouncyCastleProvider();

    public EthereumCredentials()
    {
        try
        {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", provider);
            ECGenParameterSpec ecGenParameterSpec = new ECGenParameterSpec("secp256k1");
            keyPairGenerator.initialize(ecGenParameterSpec, new SecureRandom());
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
            this.address = EthereumUtil.toAddress(((BCECPublicKey)publicKey).getQ());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public String getAddress()
    {
        return address;
    }

    public EthereumAuthenticator.SignedMessage signMessage(String message) throws Exception
    {
        byte[] messageBytes = message.getBytes(StandardCharsets.ISO_8859_1);
        String prefix = "\u0019Ethereum Signed Message:\n" + messageBytes.length + message;
        byte[] messageHash = keccak256(prefix.getBytes(StandardCharsets.ISO_8859_1));

        Signature ecdsaSign = Signature.getInstance("NONEwithECDSA", provider);
        ecdsaSign.initSign(privateKey);
        ecdsaSign.update(messageHash);
        byte[] encodedSignature = ecdsaSign.sign();
        byte[] r = getR(encodedSignature);
        byte[] s = getS(encodedSignature);

        byte[] signature = new byte[65];
        System.arraycopy(r, 0, signature, 0, 32);
        System.arraycopy(s, 0, signature, 32, 32);
        signature[64] = (byte)(calculateV(messageHash, r, s) + 27);
        return new EthereumAuthenticator.SignedMessage(message, Hex.toHexString(signature));
    }

    private byte[] getR(byte[] encodedSignature)
    {
        int rLength = encodedSignature[3];
        byte[] r = Arrays.copyOfRange(encodedSignature, 4, 4 + rLength);
        return ensure32Bytes(r);
    }

    private byte[] getS(byte[] encodedSignature)
    {
        int rLength = encodedSignature[3];
        int sLength = encodedSignature[5 + rLength];
        byte[] s = Arrays.copyOfRange(encodedSignature, 6 + rLength, 6 + rLength + sLength);
        return ensure32Bytes(s);
    }

    private byte[] ensure32Bytes(byte[] bytes)
    {
        if (bytes.length == 32)
            return bytes;
        if (bytes.length > 32)
            return Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        else
        {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
            return padded;
        }
    }

    private byte calculateV(byte[] hash, byte[] r, byte[] s)
    {
        ECPoint publicKeyPoint = ((BCECPublicKey)publicKey).getQ();
        for (int v = 0; v < 4; v++)
        {
            ECPoint qPoint = EthereumUtil.ecRecover(hash, v, new BigInteger(1, r), new BigInteger(1, s));
            if (qPoint != null && qPoint.equals(publicKeyPoint))
                return (byte)v;
        }
        throw new RuntimeException("Could not recover public key from signature");
    }
}
