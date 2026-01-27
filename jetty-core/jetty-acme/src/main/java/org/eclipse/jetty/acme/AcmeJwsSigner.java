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

package org.eclipse.jetty.acme;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.util.ajax.JSON;

/**
 * <p>JWS (JSON Web Signature) signer for ACME protocol requests.</p>
 * <p>All ACME requests (except directory and newNonce) must be signed
 * using JWS with a Flattened JSON Serialization format.</p>
 *
 * <p>Supports RSA (RS256) and ECDSA (ES256, ES384) key algorithms.</p>
 */
public class AcmeJwsSigner
{
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair _accountKeyPair;
    private final JSON _json;
    private String _accountUrl;

    /**
     * Creates a new JWS signer with the given account key pair.
     *
     * @param accountKeyPair the key pair for signing
     * @param json the JSON parser/generator
     */
    public AcmeJwsSigner(KeyPair accountKeyPair, JSON json)
    {
        _accountKeyPair = Objects.requireNonNull(accountKeyPair, "accountKeyPair");
        _json = Objects.requireNonNull(json, "json");
    }

    /**
     * Sets the account URL (kid) to use in JWS headers after account creation.
     *
     * @param accountUrl the account URL
     */
    public void setAccountUrl(String accountUrl)
    {
        _accountUrl = accountUrl;
    }

    /**
     * @return the account URL, or null if not yet set
     */
    public String getAccountUrl()
    {
        return _accountUrl;
    }

    /**
     * Signs a payload for an ACME request.
     *
     * @param payload the payload object (will be JSON encoded), or null for POST-as-GET
     * @param nonce the replay nonce from the ACME server
     * @param url the URL being requested
     * @return the JWS JSON string
     * @throws AcmeException if signing fails
     */
    public String sign(Object payload, String nonce, String url) throws AcmeException
    {
        try
        {
            // Build protected header
            Map<String, Object> protectedHeader = new LinkedHashMap<>();
            protectedHeader.put("alg", getAlgorithm());
            protectedHeader.put("nonce", nonce);
            protectedHeader.put("url", url);

            // Use kid (account URL) if available, otherwise jwk (public key)
            if (_accountUrl != null)
            {
                protectedHeader.put("kid", _accountUrl);
            }
            else
            {
                protectedHeader.put("jwk", getJwk());
            }

            // Encode protected header
            String protectedJson = _json.toJSON(protectedHeader);
            String protectedB64 = base64UrlEncode(protectedJson.getBytes(StandardCharsets.UTF_8));

            // Encode payload (empty string for POST-as-GET)
            String payloadB64;
            if (payload == null)
            {
                payloadB64 = "";
            }
            else
            {
                String payloadJson = _json.toJSON(payload);
                payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            }

            // Sign
            String signingInput = protectedB64 + "." + payloadB64;
            byte[] signatureBytes = computeSignature(signingInput.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = base64UrlEncode(signatureBytes);

            // Build JWS JSON
            Map<String, String> jwsMap = new LinkedHashMap<>();
            jwsMap.put("protected", protectedB64);
            jwsMap.put("payload", payloadB64);
            jwsMap.put("signature", signatureB64);

            return _json.toJSON(jwsMap);
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to sign JWS", e);
        }
    }

    /**
     * Computes the key authorization for an HTTP-01 challenge.
     * This is the token concatenated with the account key thumbprint.
     *
     * @param token the challenge token
     * @return the key authorization string
     * @throws AcmeException if computation fails
     */
    public String computeKeyAuthorization(String token) throws AcmeException
    {
        try
        {
            String thumbprint = computeThumbprint();
            return token + "." + thumbprint;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to compute key authorization", e);
        }
    }

    /**
     * Computes the JWK thumbprint of the account public key.
     *
     * @return the base64url-encoded thumbprint
     * @throws NoSuchAlgorithmException if SHA-256 is not available
     */
    public String computeThumbprint() throws NoSuchAlgorithmException
    {
        Map<String, Object> jwk = getJwk();

        // Create canonical JWK JSON (keys in lexicographic order)
        StringBuilder canonical = new StringBuilder("{");
        PublicKey publicKey = _accountKeyPair.getPublic();

        if (publicKey instanceof RSAPublicKey)
        {
            // RSA: {"e":"...","kty":"RSA","n":"..."}
            canonical.append("\"e\":\"").append(jwk.get("e")).append("\",");
            canonical.append("\"kty\":\"RSA\",");
            canonical.append("\"n\":\"").append(jwk.get("n")).append("\"");
        }
        else if (publicKey instanceof ECPublicKey)
        {
            // EC: {"crv":"...","kty":"EC","x":"...","y":"..."}
            canonical.append("\"crv\":\"").append(jwk.get("crv")).append("\",");
            canonical.append("\"kty\":\"EC\",");
            canonical.append("\"x\":\"").append(jwk.get("x")).append("\",");
            canonical.append("\"y\":\"").append(jwk.get("y")).append("\"");
        }

        canonical.append("}");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(hash);
    }

    /**
     * Gets the JWK (JSON Web Key) representation of the account public key.
     *
     * @return the JWK as a map
     */
    public Map<String, Object> getJwk()
    {
        Map<String, Object> jwk = new LinkedHashMap<>();
        PublicKey publicKey = _accountKeyPair.getPublic();

        if (publicKey instanceof RSAPublicKey rsaKey)
        {
            jwk.put("kty", "RSA");
            jwk.put("e", base64UrlEncode(toUnsignedBytes(rsaKey.getPublicExponent())));
            jwk.put("n", base64UrlEncode(toUnsignedBytes(rsaKey.getModulus())));
        }
        else if (publicKey instanceof ECPublicKey ecKey)
        {
            ECParameterSpec params = ecKey.getParams();
            int fieldSize = params.getCurve().getField().getFieldSize();
            String crv = getCurveName(fieldSize);

            jwk.put("kty", "EC");
            jwk.put("crv", crv);
            jwk.put("x", base64UrlEncode(toFixedLengthBytes(ecKey.getW().getAffineX(), fieldSize)));
            jwk.put("y", base64UrlEncode(toFixedLengthBytes(ecKey.getW().getAffineY(), fieldSize)));
        }

        return jwk;
    }

    private String getAlgorithm()
    {
        PublicKey publicKey = _accountKeyPair.getPublic();
        if (publicKey instanceof RSAPublicKey)
        {
            return "RS256";
        }
        else if (publicKey instanceof ECPublicKey ecKey)
        {
            int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();
            return switch (fieldSize)
            {
                case 256 -> "ES256";
                case 384 -> "ES384";
                case 521 -> "ES512";
                default -> throw new IllegalStateException("Unsupported EC key size: " + fieldSize);
            };
        }
        throw new IllegalStateException("Unsupported key type: " + publicKey.getAlgorithm());
    }

    private byte[] computeSignature(byte[] data) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException
    {
        PublicKey publicKey = _accountKeyPair.getPublic();
        String algorithm;

        if (publicKey instanceof RSAPublicKey)
        {
            algorithm = "SHA256withRSA";
        }
        else if (publicKey instanceof ECPublicKey ecKey)
        {
            int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();
            algorithm = switch (fieldSize)
            {
                case 256 -> "SHA256withECDSA";
                case 384 -> "SHA384withECDSA";
                case 521 -> "SHA512withECDSA";
                default -> throw new IllegalStateException("Unsupported EC key size: " + fieldSize);
            };
        }
        else
        {
            throw new IllegalStateException("Unsupported key type: " + publicKey.getAlgorithm());
        }

        Signature sig = Signature.getInstance(algorithm);
        sig.initSign(_accountKeyPair.getPrivate());
        sig.update(data);
        byte[] signatureBytes = sig.sign();

        // For ECDSA, convert from DER to R||S format as required by JWS
        if (publicKey instanceof ECPublicKey ecKey)
        {
            int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();
            signatureBytes = derToConcat(signatureBytes, (fieldSize + 7) / 8);
        }

        return signatureBytes;
    }

    private String getCurveName(int fieldSize)
    {
        return switch (fieldSize)
        {
            case 256 -> "P-256";
            case 384 -> "P-384";
            case 521 -> "P-521";
            default -> throw new IllegalStateException("Unsupported EC curve size: " + fieldSize);
        };
    }

    private static String base64UrlEncode(byte[] data)
    {
        return URL_ENCODER.encodeToString(data);
    }

    private static byte[] toUnsignedBytes(BigInteger value)
    {
        byte[] bytes = value.toByteArray();
        // Remove leading zero byte if present (sign byte)
        if (bytes.length > 1 && bytes[0] == 0)
        {
            byte[] result = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, result, 0, result.length);
            return result;
        }
        return bytes;
    }

    private static byte[] toFixedLengthBytes(BigInteger value, int bitLength)
    {
        int byteLength = (bitLength + 7) / 8;
        byte[] bytes = toUnsignedBytes(value);

        if (bytes.length == byteLength)
            return bytes;

        byte[] result = new byte[byteLength];
        if (bytes.length > byteLength)
        {
            System.arraycopy(bytes, bytes.length - byteLength, result, 0, byteLength);
        }
        else
        {
            System.arraycopy(bytes, 0, result, byteLength - bytes.length, bytes.length);
        }
        return result;
    }

    /**
     * Converts ECDSA DER signature to R||S concatenated format.
     */
    private static byte[] derToConcat(byte[] derSignature, int componentLength)
    {
        // DER format: 0x30 [length] 0x02 [r-length] [r] 0x02 [s-length] [s]
        int offset = 0;

        // Skip SEQUENCE tag and length
        if (derSignature[offset++] != 0x30)
            throw new IllegalArgumentException("Invalid DER signature");

        int sequenceLength = derSignature[offset++] & 0xFF;
        if ((sequenceLength & 0x80) != 0)
        {
            // Long form length
            int numLengthBytes = sequenceLength & 0x7F;
            offset += numLengthBytes;
        }

        // Read R
        if (derSignature[offset++] != 0x02)
            throw new IllegalArgumentException("Invalid DER signature");

        int rLength = derSignature[offset++] & 0xFF;
        byte[] r = new byte[rLength];
        System.arraycopy(derSignature, offset, r, 0, rLength);
        offset += rLength;

        // Read S
        if (derSignature[offset++] != 0x02)
            throw new IllegalArgumentException("Invalid DER signature");

        int sLength = derSignature[offset++] & 0xFF;
        byte[] s = new byte[sLength];
        System.arraycopy(derSignature, offset, s, 0, sLength);

        // Convert to fixed-length concatenated format
        byte[] result = new byte[componentLength * 2];

        // Copy R (skip leading zeros, pad if needed)
        int rOffset = (r.length > componentLength) ? r.length - componentLength : 0;
        int rCopyLen = Math.min(r.length, componentLength);
        System.arraycopy(r, rOffset, result, componentLength - rCopyLen, rCopyLen);

        // Copy S (skip leading zeros, pad if needed)
        int sOffset = (s.length > componentLength) ? s.length - componentLength : 0;
        int sCopyLen = Math.min(s.length, componentLength);
        System.arraycopy(s, sOffset, result, componentLength * 2 - sCopyLen, sCopyLen);

        return result;
    }
}
