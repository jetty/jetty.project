#!/bin/bash
set -e

readonly CERTS_DIR="certs"
readonly PASSWORD="storepwd"
#certificate authorities
readonly INTERMEDIATE_CA="intermediate_ca"
readonly ROOT_CA="root_ca"
readonly UNTRUSTED="untrusted"

#Create certs directory and use it as the PWD
function generate_certs_dir() {
    if [ -d $CERTS_DIR ]; then
        rm -r $CERTS_DIR
    fi

    mkdir $CERTS_DIR
    pushd $CERTS_DIR
}

function generate_private_key() {
    local -r hostname=$1
    openssl genrsa \
        -aes256 \
        -passout pass:$PASSWORD \
        -out "$hostname"/"$hostname".key \
        4096
}

function generate_csr() {
    local -r hostname=$1

    openssl req \
        -new \
        -noenc \
        -key "$hostname"/"$hostname".key \
        -passin pass:$PASSWORD \
        -out "$hostname"/"$hostname".csr \
        -subj "/C=AA/ST=BB/L=CC/O=DD/OU=EE/CN=$hostname"
}

function generate_keystore() {
    local -r hostname=$1
    openssl pkcs12 -export \
        -in "$hostname"/"$hostname".pem \
        -inkey "$hostname"/"$hostname".key \
        -passin pass:$PASSWORD \
        -out "$hostname"/"$hostname".p12 \
        -name "$hostname" \
        -passout pass:$PASSWORD
}

#Generate certificates
function generate_root_ca() {
    mkdir $ROOT_CA

    generate_private_key $ROOT_CA

    openssl req \
        -new \
        -key $ROOT_CA/$ROOT_CA.key \
        -passin pass:$PASSWORD \
        -days 36500 \
        -nodes \
        -x509 \
        -subj "/C=AA/ST=BB/L=CC/O=DD/CN=$ROOT_CA" \
        -out $ROOT_CA/$ROOT_CA.pem
}

#Intermediate CA
function generate_intermediate_ca() {
    mkdir $INTERMEDIATE_CA

    #create intermediate ca configuration file
    cat > $INTERMEDIATE_CA/$INTERMEDIATE_CA.cnf <<EOL
    authorityKeyIdentifier=keyid,issuer
    basicConstraints=CA:TRUE
    keyUsage = keyCertSign, cRLSign, digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
EOL
    generate_private_key $INTERMEDIATE_CA

    generate_csr $INTERMEDIATE_CA

    #create certificate
    openssl x509 -req \
        -in $INTERMEDIATE_CA/$INTERMEDIATE_CA.csr \
        -CA $ROOT_CA/$ROOT_CA.pem \
        -CAkey $ROOT_CA/$ROOT_CA.key \
        -passin pass:$PASSWORD \
        -CAcreateserial \
        -out $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem \
        -days 36500 \
        -sha256 \
        -extfile $INTERMEDIATE_CA/$INTERMEDIATE_CA.cnf

    cat $ROOT_CA/$ROOT_CA.pem >> $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem
    #cleanup
    rm $INTERMEDIATE_CA/$INTERMEDIATE_CA.cnf \
        $INTERMEDIATE_CA/$INTERMEDIATE_CA.csr
}

function generate_server_leaf() {
    local -r hostname=$1

    mkdir "$hostname"

        #create intermediate ca configuration file
    cat > "$hostname"/"$hostname".cnf <<EOL
    subjectAltName = DNS:localhost, DNS:[::1], DNS:[0:0:0:0:0:0:0:1], IP:127.0.0.1, IP:127.0.1.1, IP:0:0:0:0:0:0:0:1
EOL

    generate_private_key "$hostname"

    generate_csr "$hostname"

    #sign csr and generate certificate
    openssl x509 -req \
        -in "$hostname"/"$hostname".csr \
        -CA $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem \
        -CAkey $INTERMEDIATE_CA/$INTERMEDIATE_CA.key \
        -passin pass:$PASSWORD \
        -CAcreateserial \
        -out "$hostname"/"$hostname".pem \
        -days 36500 \
        -sha256 \
        -extfile "$hostname"/"$hostname".cnf
    #chain certificate
    cat $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem >> "$hostname"/"$hostname".pem

    generate_keystore "$hostname"

    #cleanup
    rm "$hostname"/"$hostname".cnf \
        "$hostname"/"$hostname".csr
}

function generate_signed_leaf() {
    local -r hostname=$1

    mkdir "$hostname"

    generate_private_key "$hostname"

    generate_csr "$hostname"

    #sign csr and generate certificate
    openssl x509 -req \
        -in "$hostname"/"$hostname".csr \
        -CA $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem \
        -CAkey $INTERMEDIATE_CA/$INTERMEDIATE_CA.key \
        -passin pass:$PASSWORD \
        -CAcreateserial \
        -out "$hostname"/"$hostname".pem \
        -days 36500 \
        -sha256
    #chain certificate
    cat $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem >> "$hostname"/"$hostname".pem

    generate_keystore "$hostname"

    #cleanup
    rm "$hostname"/"$hostname".csr
}

#Untrusted Certificate
function generate_unsigned_leaf() {
    mkdir $UNTRUSTED

    generate_private_key $UNTRUSTED

    generate_csr $UNTRUSTED

    #create cert
    openssl x509  -req \
        -in $UNTRUSTED/$UNTRUSTED.csr \
        -signkey $UNTRUSTED/$UNTRUSTED.key \
        -passin pass:$PASSWORD \
        -out $UNTRUSTED/$UNTRUSTED.pem \
        -days 36500 \
        -sha256

    generate_keystore $UNTRUSTED

    #cleanup
    rm $UNTRUSTED/$UNTRUSTED.csr
}

function generate_trust_store() {
      #trust store
      for hostname in $ROOT_CA $INTERMEDIATE_CA; do
          keytool -import \
              -file "$hostname"/"$hostname".pem \
              -alias "$hostname" \
              -keystore trustStore.jks \
              -storepass $PASSWORD \
              -noprompt
      done
}

######
#MAIN#
######
generate_certs_dir
generate_root_ca
generate_intermediate_ca

for hostname in server proxy; do
  generate_server_leaf "$hostname"
done

for hostname in client; do
    generate_signed_leaf "$hostname"
done
generate_unsigned_leaf

generate_trust_store

popd
