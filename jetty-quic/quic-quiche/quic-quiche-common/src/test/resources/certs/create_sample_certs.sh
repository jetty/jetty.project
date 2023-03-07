#!/bin/bash
set -e

readonly CERTS_DIR="certs"
readonly INTERMEDIATE_CA="intermediate_ca"
readonly PASSWORD="password"
readonly ROOT_CA="root_ca"
readonly UNTRUSTED="untrusted"

#Create certs directory and use it as the PWD
if [ -d $CERTS_DIR ]; then
    rm $CERTS_DIR
fi
mkdir $CERTS_DIR
pushd $CERTS_DIR

#Generate certifiactes

#Root CA
mkdir $ROOT_CA
openssl req -new -newkey rsa:4096 -days 36500 -nodes -x509 \
-subj "/C=AA/ST=BB/L=CC/O=DD/CN=$ROOT_CA" \
-keyout $ROOT_CA/$ROOT_CA.key  -out $ROOT_CA/$ROOT_CA.pem

#Intermediate CA
mkdir $INTERMEDIATE_CA
#create intermediate ca configuration file
cat > $INTERMEDIATE_CA/$INTERMEDIATE_CA.cnf <<EOL 
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:TRUE
keyUsage = keyCertSign, cRLSign, digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
EOL
#create key and csr
openssl req -noenc -newkey rsa:4096 -keyout $INTERMEDIATE_CA/$INTERMEDIATE_CA.key \
-out $INTERMEDIATE_CA/$INTERMEDIATE_CA.csr -subj "/C=AA/ST=BB/L=CC/O=DD/OU=EE/CN=$INTERMEDIATE_CA"
#create certificate
openssl x509 -req -in $INTERMEDIATE_CA/$INTERMEDIATE_CA.csr -CA $ROOT_CA/$ROOT_CA.pem -CAkey $ROOT_CA/$ROOT_CA.key -CAcreateserial -out $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem -days 36500 -sha256 -extfile $INTERMEDIATE_CA/$INTERMEDIATE_CA.cnf 
cat $ROOT_CA/$ROOT_CA.pem >> $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem
#cleanup
rm $INTERMEDIATE_CA/$INTERMEDIATE_CA.cnf $INTERMEDIATE_CA/$INTERMEDIATE_CA.csr

#Certificates
for hostname in localhost client; do

	mkdir $hostname

	openssl req -noenc -newkey rsa:4096 -keyout $hostname/$hostname.key \
	-out $hostname/$hostname.csr -subj "/C=AA/ST=BB/L=CC/O=DD/OU=EE/CN=$hostname"
	openssl x509 -req -in $hostname/$hostname.csr -CA $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem -CAkey $INTERMEDIATE_CA/$INTERMEDIATE_CA.key -CAcreateserial -out $hostname/$hostname.pem -days 36500 -sha256
	#chain certificate
	cat $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem >> $hostname/$hostname.pem
	#create keystore
	openssl pkcs12 -export -in $hostname/$hostname.pem -inkey $hostname/$hostname.key -out $hostname/$hostname.p12 \
	-name $hostname  -passout pass:$PASSWORD
	#cleanup
	rm $hostname/$hostname.csr

done

#Untrusted Certificate
mkdir $UNTRUSTED
openssl req -new -newkey rsa:4096 -days 36500 -nodes -x509 \
-subj "/C=AA/ST=BB/L=CC/O=DD/CN=$UNTRUSTED" \
-keyout $UNTRUSTED/$UNTRUSTED.key  -out $UNTRUSTED/$UNTRUSTED.pem
#create keystore
openssl pkcs12 -export -in $UNTRUSTED/$UNTRUSTED.pem -inkey $UNTRUSTED/$UNTRUSTED.key -out $UNTRUSTED/$UNTRUSTED.p12 \
-name $hostname  -passout pass:$PASSWORD

#trust store
keytool -import -file $ROOT_CA/$ROOT_CA.pem -alias $ROOT_CA -keystore trustStore.jks -storepass $PASSWORD -noprompt
keytool -import -file $INTERMEDIATE_CA/$INTERMEDIATE_CA.pem -alias $INTERMEDIATE_CA -keystore trustStore.jks -storepass $PASSWORD -noprompt

popd