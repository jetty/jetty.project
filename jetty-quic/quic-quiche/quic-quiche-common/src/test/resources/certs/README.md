# Create Sample Certificates
The file create_sample_certs.sh generates a set of a private keys, public certificates, and PKCS12 keyStores for testing in the subdirectory certs.

## Root Certificate Authority
The root ca signs the intermediate certificate authority.  This is the root of the chain of trust.  There is not keystore for this entry.

## Intermediate Certificate Authority
The intermediate ca is signed by the root ca and signs other certificates in the chain of trust.

## Localhost
This localhost is a leaf certificate signed by the intermediate ca.  It is useful for this to be named localhost for use with servers, since some clients compare the hostname to the certificate common name.

## Client
The client is a leaf certificate signed by the intermediate ca.

## Untrusted
The untrusted is a self-signed leaf certificate.  It is useful to have an entry outside the chain of trust to verify trust is working. 

## Trust Store
The trust store is a JKS key store with the trusted ca certificates, root and intermediate.