Client Certificate Authentication Test Keystores

These keystores support mutual TLS (client certificate) authentication testing.

## Certificate Properties

- **Signature Algorithm:** SHA256withRSA (client cert), SHA384withRSA (server cert)
- **Key Size:** 2048-bit RSA
- **Validity:** 100 years (36500 days) (yup we should be retired by then)
- **Format:** PKCS12
- **Password:** changeit (all keystores)

## Distinguished Name

The certificate DN must match realm.properties for authentication to succeed:
```
CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US
```

## Files

- **server_keystore.p12** - Server private key + self-signed certificate (acts as CA)
- **client_keystore.p12** - Client private key + certificate chain (signed by server CA)
- **truststore.p12** - Server CA certificate (for server to trust client certificates)

## Pre-Generated Keystores

These keystores are pre-generated and committed to the repository (located in
src/test/resources). This approach:
- Works on all platforms (no openssl dependency)
- Provides deterministic builds (same certificates for all developers)
- Follows the standard Jetty pattern (see jetty-client tests)

## Regenerating Keystores (if needed)

To regenerate keystores manually, run these commands from target/test-classes:

```bash
# 1. Generate server keystore with CA capabilities
keytool -genkeypair -validity 36500 -keyalg RSA -keysize 2048 \
  -keystore server_keystore.p12 -storetype pkcs12 \
  -storepass changeit -keypass changeit \
  -dname "CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US" \
  -ext bc=ca:true \
  -ext san=ip:127.0.0.1,ip:[::1],dns:localhost

# 2. Export server certificate (PEM format)
keytool -export -keystore server_keystore.p12 -storepass changeit -rfc -file server.crt

# 3. Export server private key (for CSR signing)
openssl pkcs12 -in server_keystore.p12 -passin pass:changeit -nodes -nocerts -out server.key

# 4. Generate client keystore
keytool -genkeypair -validity 36500 -keyalg RSA -keysize 2048 \
  -keystore client_keystore.p12 -storetype pkcs12 \
  -storepass changeit -keypass changeit \
  -dname "CN=CTS, OU=Java Software, O=Sun Microsystems Inc., L=Burlington, ST=MA, C=US"

# 5. Generate Certificate Signing Request from client keystore
keytool -certreq -file client.csr -keystore client_keystore.p12 -storepass changeit

# 6. Sign client CSR with server CA
openssl x509 -req -days 36500 -in client.csr \
  -CA server.crt -CAkey server.key -CAcreateserial -sha256 \
  -out client_signed.crt

# 7. Import server CA certificate into client keystore
keytool -import -alias ca -file server.crt \
  -keystore client_keystore.p12 -storepass changeit -noprompt

# 8. Import signed client certificate (completes the chain)
keytool -import -file client_signed.crt \
  -keystore client_keystore.p12 -storepass changeit -noprompt

# 9. Create truststore for server
keytool -import -alias ca -file server.crt \
  -keystore truststore.p12 -storetype pkcs12 \
  -storepass changeit -noprompt

# 10. Clean up temporary files
rm -f server.crt server.key server.srl client.csr client_signed.crt
```

## Verification

Check certificate properties:
```bash
# View server keystore
keytool -list -v -keystore server_keystore.p12 -storepass changeit

# View client keystore (should show chain length: 2)
keytool -list -v -keystore client_keystore.p12 -storepass changeit

# View truststore
keytool -list -v -keystore truststore.p12 -storepass changeit
```

Expected output:
- No "DISABLED" warnings
- Signature algorithm: SHA256withRSA or SHA384withRSA (NOT MD5withRSA)
- Key size: 2048-bit RSA
- Client keystore contains 2 entries: "ca" (trusted cert) + "mykey" (private key with chain)
