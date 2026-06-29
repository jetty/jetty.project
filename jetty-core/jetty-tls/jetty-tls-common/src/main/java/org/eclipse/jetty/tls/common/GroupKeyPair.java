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

package org.eclipse.jetty.tls.common;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;

import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.TLSException;

import static org.eclipse.jetty.tls.NamedGroup.ffdhe2048_code;
import static org.eclipse.jetty.tls.NamedGroup.ffdhe3072_code;
import static org.eclipse.jetty.tls.NamedGroup.ffdhe4096_code;
import static org.eclipse.jetty.tls.NamedGroup.ffdhe6144_code;
import static org.eclipse.jetty.tls.NamedGroup.ffdhe8192_code;
import static org.eclipse.jetty.tls.NamedGroup.secp256r1_code;
import static org.eclipse.jetty.tls.NamedGroup.secp384r1_code;
import static org.eclipse.jetty.tls.NamedGroup.secp521r1_code;
import static org.eclipse.jetty.tls.NamedGroup.x25519_code;
import static org.eclipse.jetty.tls.NamedGroup.x448_code;

public record GroupKeyPair(NamedGroup group, KeyPair keyPair)
{
    public static GroupKeyPair from(NamedGroup group) throws Exception
    {
        return switch (group.code())
        {
            case x448_code, x25519_code ->
            {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(group.name());
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                yield new GroupKeyPair(group, keyPair);
            }
            case secp256r1_code, secp384r1_code, secp521r1_code ->
            {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
                keyPairGenerator.initialize(new ECGenParameterSpec(group.name()));
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                yield new GroupKeyPair(group, keyPair);
            }
            case ffdhe2048_code, ffdhe3072_code, ffdhe4096_code, ffdhe6144_code, ffdhe8192_code ->
            {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
                keyPairGenerator.initialize(Integer.parseInt(group.name().substring("ffdhe".length())));
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                yield new GroupKeyPair(group, keyPair);
            }
            default -> throw new UnsupportedOperationException("unknown " + group);
        };
    }

    public KeyShare toKeyShare()
    {
        return switch (group().code())
        {
            case x25519_code, x448_code ->
            {
                // RFC 8446, Section 4.2.8.2.
                XECPublicKey publicKey = (XECPublicKey)keyPair().getPublic();
                byte[] bytes = toFixedLengthBytes(publicKey.getU(), length(group()));
                // RFC 7748, Section 5: the coordinate is little-endian, BigInteger returns big-endian.
                yield new KeyShare(group, reverse(bytes));
            }
            case secp256r1_code, secp384r1_code, secp521r1_code ->
            {
                // RFC 8446, Section 4.2.8.2.
                ECPublicKey publicKey = (ECPublicKey)keyPair().getPublic();
                ECPoint w = publicKey.getW();
                int length = length(group());
                byte[] x = toFixedLengthBytes(w.getAffineX(), length);
                byte[] y = toFixedLengthBytes(w.getAffineY(), length);
                byte[] keyShare = new byte[1 + x.length + y.length];
                keyShare[0] = 0x04; // Uncompressed point.
                System.arraycopy(x, 0, keyShare, 1, x.length);
                System.arraycopy(y, 0, keyShare, 1 + x.length, y.length);
                yield new KeyShare(group, keyShare);
            }
            case ffdhe2048_code, ffdhe3072_code, ffdhe4096_code, ffdhe6144_code, ffdhe8192_code ->
            {
                // RFC 8446, Section 4.2.8.1.
                DHPublicKey pub = (DHPublicKey)keyPair().getPublic();
                yield new KeyShare(group, toFixedLengthBytes(pub.getY(), length(group())));
            }
            default -> throw new UnsupportedOperationException("unknown " + group);
        };
    }

    /// Verifies the received [KeyShare] and then generates
    /// a shared secret combining the private key of this `GroupKeyPair`
    /// with the public key of the received [KeyShare].
    public SecretKey generateSharedSecret(KeyShare keyShare)
    {
        try
        {
            int length = length(keyShare.namedGroup());
            return switch (group().code())
            {
                case x25519_code -> xGenerateSharedSecret(keyShare, NamedParameterSpec.X25519, length);
                case x448_code -> xGenerateSharedSecret(keyShare, NamedParameterSpec.X448, length);
                case secp256r1_code -> secpGenerateSharedSecret(keyShare, length);
                case secp384r1_code -> secpGenerateSharedSecret(keyShare, length);
                case secp521r1_code -> secpGenerateSharedSecret(keyShare, length);
                case ffdhe2048_code -> ffdheGenerateSharedSecret(keyShare, length);
                case ffdhe3072_code -> ffdheGenerateSharedSecret(keyShare, length);
                case ffdhe4096_code -> ffdheGenerateSharedSecret(keyShare, length);
                case ffdhe6144_code -> ffdheGenerateSharedSecret(keyShare, length);
                case ffdhe8192_code -> ffdheGenerateSharedSecret(keyShare, length);
                default -> throw new UnsupportedOperationException("unknown " + group);
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

    private SecretKey xGenerateSharedSecret(KeyShare keyShare, NamedParameterSpec namedParameterSpec, int length) throws Exception
    {
        // Verify.
        byte[] bytes = keyShare.keyExchange();
        if (bytes.length != length)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");

        // Generate.
        String groupName = group().name();
        KeyFactory keyFactory = KeyFactory.getInstance(groupName);
        // RFC 7748, Section 5: the coordinate is little-endian, BigInteger expects big-endian.
        BigInteger coordinate = new BigInteger(1, GroupKeyPair.reverse(bytes));
        XECPublicKeySpec keySpec = new XECPublicKeySpec(namedParameterSpec, coordinate);
        PublicKey publicKey = keyFactory.generatePublic(keySpec);
        KeyAgreement keyAgreement = KeyAgreement.getInstance(groupName);
        keyAgreement.init(keyPair().getPrivate());
        keyAgreement.doPhase(publicKey, true);
        SecretKey secretKey = keyAgreement.generateSecret("Generic");
        for (byte b : secretKey.getEncoded())
        {
            if (b != 0)
                return secretKey;
        }
        throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
    }

    private SecretKey secpGenerateSharedSecret(KeyShare keyShare, int coordinateLength) throws Exception
    {
        // Verify.
        byte[] keyExchange = keyShare.keyExchange();
        if (keyExchange.length != 1 + 2 * coordinateLength)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
        if (keyExchange[0] != 0x04)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
        BigInteger x = new BigInteger(1, keyExchange, 1, coordinateLength);
        BigInteger y = new BigInteger(1, keyExchange, 1 + coordinateLength, coordinateLength);

        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(group().name()));
        ECParameterSpec ec = params.getParameterSpec(ECParameterSpec.class);
        EllipticCurve curve = ec.getCurve();
        BigInteger p = ((ECFieldFp)curve.getField()).getP();
        BigInteger a = curve.getA();
        BigInteger b = curve.getB();

        // Must be within the curve.
        if (x.signum() < 0 || x.compareTo(p) >= 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
        if (y.signum() < 0 || y.compareTo(p) >= 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");
        // The point must satisfy: y^2 mod p == (x^3 + ax + b) mod p.
        BigInteger lhs = y.pow(2).mod(p);
        BigInteger rhs = x.pow(3).add(a.multiply(x)).add(b).mod(p);
        if (!lhs.equals(rhs))
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");

        // Generate.
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        PublicKey serverPublicKey = keyFactory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), ec));
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(keyPair().getPrivate());
        keyAgreement.doPhase(serverPublicKey, true);
        return keyAgreement.generateSecret("Generic");
    }

    private SecretKey ffdheGenerateSharedSecret(KeyShare keyShare, int length) throws Exception
    {
        // Verify.
        byte[] keyExchange = keyShare.keyExchange();
        if (keyExchange.length != length)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");

        BigInteger y = new BigInteger(1, keyExchange);
        DHParameterSpec dhParams = ((DHPublicKey)keyPair().getPublic()).getParams();
        BigInteger p = dhParams.getP();
        // Must satisfy: 1 < Y < p − 1
        if (y.compareTo(BigInteger.ONE) <= 0 || y.compareTo(p.subtract(BigInteger.ONE)) >= 0)
            throw new TLSException(TLSException.Alert.ILLEGAL_PARAMETER, "invalid KeyShare");

        // Generate.
        KeyFactory kf = KeyFactory.getInstance("DH");
        PublicKey serverPublicKey = kf.generatePublic(new DHPublicKeySpec(y, p, dhParams.getG()));
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(keyPair().getPrivate());
        keyAgreement.doPhase(serverPublicKey, true);
        return keyAgreement.generateSecret("Generic");
    }

    private static int length(NamedGroup group)
    {
        return switch (group.code())
        {
            case x25519_code -> 32;
            case x448_code -> 56;
            case secp256r1_code -> 32;
            case secp384r1_code -> 48;
            case secp521r1_code -> 66;
            case ffdhe2048_code -> 256;
            case ffdhe3072_code -> 384;
            case ffdhe4096_code -> 512;
            case ffdhe6144_code -> 768;
            case ffdhe8192_code -> 1024;
            default -> throw new UnsupportedOperationException("unknown " + group);
        };
    }

    private static byte[] reverse(byte[] bytes)
    {
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; ++i)
        {
            result[i] = bytes[bytes.length - i - 1];
        }
        return result;
    }

    /// BigInteger.toByteArray() may return different array lengths, for
    /// example depending on the sign, so it is necessary to pad with zeros
    /// at the beginning of the array or truncate the beginning of the array.
    private static byte[] toFixedLengthBytes(BigInteger bigInteger, int length)
    {
        byte[] bytes = bigInteger.toByteArray();
        if (bytes.length == length)
            return bytes;
        if (bytes.length > length)
            return Arrays.copyOfRange(bytes, bytes.length - length, bytes.length);
        byte[] copy = new byte[length];
        System.arraycopy(bytes, 0, copy, length - bytes.length, bytes.length);
        return copy;
    }
}
