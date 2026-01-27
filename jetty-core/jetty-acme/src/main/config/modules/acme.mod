[description]
Enables ACME (Let's Encrypt) automatic certificate management.
Obtains and renews TLS certificates automatically using Jetty HttpClient.
Supports HTTP-01 challenges for domain validation.

[tags]
ssl
security
acme

[depend]
ssl-context
server
client

[lib]
lib/jetty-acme-${jetty.version}.jar

[xml]
etc/jetty-acme.xml

[ini-template]
# tag::documentation[]
### ACME Certificate Management Configuration

## Dry run mode - generates self-signed cert, no ACME calls
## Default: true (SAFE - must explicitly disable for production)
# jetty.acme.dryRun=true

## ACME provider directory URL
## Production: https://acme-v02.api.letsencrypt.org/directory
## Staging (default): https://acme-staging-v02.api.letsencrypt.org/directory
# jetty.acme.directoryUrl=https://acme-staging-v02.api.letsencrypt.org/directory

## Comma-separated domain names for certificate
## REQUIRED for production use
# jetty.acme.domains=example.com,www.example.com

## Contact email for ACME account
## REQUIRED for production use
# jetty.acme.accountEmail=admin@example.com

## Path to store account key (relative to JETTY_BASE)
# jetty.acme.accountKeyPath=acme/account.key

## Keystore path and password
# jetty.acme.keystorePath=etc/keystore.p12
# jetty.acme.keystorePassword=changeit

## Days before expiry to trigger renewal (default 30)
# jetty.acme.renewalThresholdDays=30

## Renewal check interval in seconds (default 86400 = 24 hours)
# jetty.acme.checkIntervalSeconds=86400

## Terms of service agreement
## REQUIRED for production use - must be set to true
# jetty.acme.termsOfServiceAgreed=false
# end::documentation[]
