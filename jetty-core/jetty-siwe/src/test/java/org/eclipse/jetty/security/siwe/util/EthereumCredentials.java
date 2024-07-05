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

package org.eclipse.jetty.security.siwe.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECFieldElement;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.security.siwe.SignedMessage;

public class EthereumCredentials {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String address;
    private static final ECParameterSpec ecSpec;

    static {
        Security.addProvider(new BouncyCastleProvider());
        ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1");
    }

    public EthereumCredentials() {
        try {
            KeyPair keyPair = generateECKeyPair();
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
            this.address = computeAddress(publicKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private KeyPair generateECKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        ECGenParameterSpec ecGenParameterSpec = new ECGenParameterSpec("secp256k1");
        keyPairGenerator.initialize(ecGenParameterSpec, new SecureRandom());
        return keyPairGenerator.generateKeyPair();
    }

    public String getAddress() {
        return address;
    }

    public SignedMessage signMessage(String message) throws Exception {
        byte[] messageBytes = message.getBytes(StandardCharsets.ISO_8859_1);
        String prefix = "\u0019Ethereum Signed Message:\n" + messageBytes.length + message;
        byte[] messageHash = keccak256(prefix.getBytes(StandardCharsets.ISO_8859_1));

        Signature ecdsaSign = Signature.getInstance("NONEwithECDSA", BouncyCastleProvider.PROVIDER_NAME);
        ecdsaSign.initSign(privateKey);
        ecdsaSign.update(messageHash);
        byte[] derSignature = ecdsaSign.sign();

        // Decode the DER signature to get r and s
        int rLength = derSignature[3];
        byte[] r = Arrays.copyOfRange(derSignature, 4, 4 + rLength);
        int sLength = derSignature[5 + rLength];
        byte[] s = Arrays.copyOfRange(derSignature, 6 + rLength, 6 + rLength + sLength);

        // Ensure r and s are exactly 32 bytes
        r = ensure32Bytes(r);
        s = ensure32Bytes(s);

        // Calculate v
        BigInteger bigR = new BigInteger(1, r);
        BigInteger bigS = new BigInteger(1, s);
        byte v = calculateV(r, s, messageHash);

        System.err.println("r: " + bigR);
        System.err.println("s: " + bigS);
        System.err.println("v: " + v);
        ECPoint q = ((BCECPublicKey)publicKey).getQ();
        for (int i  = 0; i < 4; i++)
        {
            ECPoint ecPoint = recoverFromSignature(i, bigR, bigS, new BigInteger(1, messageHash));
            System.err.println(q.equals(ecPoint) + " " + ecPoint);
        }

        // Concatenate r, s, and v to get the final 65-byte signature
        byte[] signature = new byte[65];
        System.arraycopy(r, 0, signature, 0, 32);
        System.arraycopy(s, 0, signature, 32, 32);
        signature[64] = v;

        System.err.println("generated: ");

        String signatureHex = Hex.toHexString(signature);
        return new SignedMessage(message, signatureHex);
    }

    private byte[] ensure32Bytes(byte[] bytes) {
        if (bytes.length == 32) {
            return bytes;
        } else if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
            return padded;
        } else {
            return Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        }
    }

    private byte calculateV(byte[] r, byte[] s, byte[] messageHash) {
        BigInteger R = new BigInteger(1, r);
        BigInteger S = new BigInteger(1, s);
        ECPoint publicKeyPoint = ((BCECPublicKey) publicKey).getQ();
        for (int i = 0; i < 4; i++) {
            ECPoint Q = recoverFromSignature(i, R, S, new BigInteger(1, messageHash));
            if (Q != null && Q.equals(publicKeyPoint)) {
                return (byte) (i + 27);
            }
        }
        throw new RuntimeException("Could not recover public key from signature");
    }

    private ECPoint recoverFromSignature(int recId, BigInteger r, BigInteger s, BigInteger messageHash) {
        BigInteger n = ecSpec.getN();
        BigInteger i = BigInteger.valueOf((long) recId / 2);
        BigInteger x = r.add(i.multiply(n));

        if (x.compareTo(ecSpec.getCurve().getField().getCharacteristic()) >= 0) {
            return null;
        }

        ECPoint R = decompressKey(x, (recId & 1) == 1);
        if (!R.multiply(n).isInfinity()) {
            return null;
        }

        BigInteger e = messageHash;
        BigInteger eInv = e.negate().mod(n);
        BigInteger rInv = r.modInverse(n);
        BigInteger srInv = rInv.multiply(s).mod(n);
        BigInteger eInvrInv = rInv.multiply(eInv).mod(n);

        ECPoint q = ECAlgorithms.sumOfTwoMultiplies(ecSpec.getG(), eInvrInv, R, srInv);
        return q;
    }

    private ECPoint decompressKey(BigInteger xBN, boolean yBit) {
        org.bouncycastle.math.ec.ECCurve curve = ecSpec.getCurve();
        ECFieldElement x = curve.fromBigInteger(xBN);
        ECFieldElement alpha = x.square().add(curve.getA()).multiply(x).add(curve.getB());
        ECFieldElement beta = alpha.sqrt();
        if (beta == null) {
            throw new IllegalArgumentException("Invalid point compression");
        }
        if (beta.testBitZero() != yBit) {
            beta = beta.negate();
        }
        return curve.createPoint(x.toBigInteger(), beta.toBigInteger());
    }

    private byte[] keccak256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] hash = new byte[digest.getDigestSize()];
        digest.doFinal(hash, 0);
        return hash;
    }

    private String computeAddress(PublicKey publicKey) {
        ECPoint q = ((BCECPublicKey) publicKey).getQ();
        byte[] pubKeyBytes = q.getEncoded(false); // false for uncompressed point
        byte[] hash = keccak256(Arrays.copyOfRange(pubKeyBytes, 1, pubKeyBytes.length));
        return "0x" + Hex.toHexString(Arrays.copyOfRange(hash, hash.length - 20, hash.length));
    }

    public static void main(String[] args) {
        try {
            EthereumCredentials credentials = new EthereumCredentials();
            String address = credentials.getAddress();
            SignedMessage signedMessage = credentials.signMessage("Hello, Ethereum!");

            System.out.println("Address: " + address);
            System.out.println("Signed Message: " + signedMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
