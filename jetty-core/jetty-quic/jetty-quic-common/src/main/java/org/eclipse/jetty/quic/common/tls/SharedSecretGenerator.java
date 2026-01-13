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

package org.eclipse.jetty.quic.common.tls;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;

import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.tls.common.GroupKeyPair;

public class SharedSecretGenerator
{
    public static byte[] verifyAndGenerate(GroupKeyPair groupKeyPair, KeyShare keyShare)
    {
        try
        {
            return switch (keyShare.group())
            {
                case x25519 -> xGenerate(groupKeyPair, keyShare, NamedParameterSpec.X25519, 32);
                case x448 -> xGenerate(groupKeyPair, keyShare, NamedParameterSpec.X448, 56);
                case secp256r1 -> secpGenerate(groupKeyPair, keyShare, 32);
                case secp384r1 -> secpGenerate(groupKeyPair, keyShare, 48);
                case secp521r1 -> secpGenerate(groupKeyPair, keyShare, 66);
                case ffdhe2048 -> ffdheGenerate(groupKeyPair, keyShare);
                case ffdhe3072 -> ffdheGenerate(groupKeyPair, keyShare);
                case ffdhe4096 -> ffdheGenerate(groupKeyPair, keyShare);
                case ffdhe6144 -> ffdheGenerate(groupKeyPair, keyShare);
                case ffdhe8192 -> ffdheGenerate(groupKeyPair, keyShare);
            };
        }
        catch (TLSException x)
        {
            throw x;
        }
        catch (Throwable x)
        {
            throw new TLSException(TLSException.Alert.INTERNAL_ERROR, x);
        }
    }

    private static byte[] xGenerate(GroupKeyPair groupKeyPair, KeyShare keyShare, NamedParameterSpec namedParameterSpec, int length) throws Exception
    {
        // Verify.
        if (keyShare.keyExchange().length != length)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");

        // Generate.
        String groupName = groupKeyPair.group().name();
        KeyFactory keyFactory = KeyFactory.getInstance(groupName);
        XECPublicKeySpec keySpec = new XECPublicKeySpec(namedParameterSpec, new BigInteger(1, keyShare.keyExchange()));
        PublicKey publicKey = keyFactory.generatePublic(keySpec);
        KeyAgreement keyAgreement = KeyAgreement.getInstance(groupName);
        keyAgreement.init(groupKeyPair.keyPair().getPrivate());
        keyAgreement.doPhase(publicKey, true);
        byte[] secret = keyAgreement.generateSecret();
        for (byte b : secret)
        {
            if (b != 0)
                throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
        }
        return secret;
    }

    private static byte[] secpGenerate(GroupKeyPair groupKeyPair, KeyShare keyShare, int coordinateLength) throws Exception
    {
        // Verify.
        byte[] keyExchange = keyShare.keyExchange();
        if (keyExchange.length != 1 + 2 * coordinateLength)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
        byte[] xBytes = new byte[coordinateLength];
        System.arraycopy(keyExchange, 1, xBytes, 0, coordinateLength);
        byte[] yBytes = new byte[coordinateLength];
        System.arraycopy(keyExchange, 1 + coordinateLength, yBytes, 0, coordinateLength);
        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y = new BigInteger(1, yBytes);

        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(groupKeyPair.group().name()));
        ECParameterSpec ec = params.getParameterSpec(ECParameterSpec.class);
        EllipticCurve curve = ec.getCurve();
        BigInteger p = ((ECFieldFp)curve.getField()).getP();
        BigInteger a = curve.getA();
        BigInteger b = curve.getB();

        // Must be within the curve.
        if (x.signum() < 0 || x.compareTo(p) >= 0 || y.signum() < 0 || y.compareTo(p) >= 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
        // The point must satisfy: y^2 mod p == x^3 + ax + b mod p.
        BigInteger lhs = y.multiply(y).mod(p);
        BigInteger rhs = x.multiply(x).multiply(x).add(a.multiply(x)).add(b).mod(p);
        if (!lhs.equals(rhs))
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");

        // Generate.
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        PublicKey serverPublicKey = keyFactory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), ec));
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(groupKeyPair.keyPair().getPrivate());
        keyAgreement.doPhase(serverPublicKey, true);
        return keyAgreement.generateSecret();
    }

    private static byte[] ffdheGenerate(GroupKeyPair groupKeyPair, KeyShare keyShare) throws Exception
    {
        // Verify.
        BigInteger y = new BigInteger(1, keyShare.keyExchange());

        AlgorithmParameters params = AlgorithmParameters.getInstance("DH");
        params.init(new NamedParameterSpec(groupKeyPair.group().name()));
        DHParameterSpec dh = params.getParameterSpec(DHParameterSpec.class);
        BigInteger p = dh.getP();
        // Must satisfy: 2 <= Y <= p − 2
        if (y.compareTo(BigInteger.TWO) < 0 || y.compareTo(p.subtract(BigInteger.TWO)) > 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");

        // Generate.
        KeyFactory kf = KeyFactory.getInstance("DH");
        PublicKey serverPublicKey = kf.generatePublic(new DHPublicKeySpec(y, p, dh.getG()));
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(groupKeyPair.keyPair().getPrivate());
        keyAgreement.doPhase(serverPublicKey, true);
        return keyAgreement.generateSecret();
    }

    private SharedSecretGenerator()
    {
    }
}
