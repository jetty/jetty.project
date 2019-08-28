DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds OpenId Connect authentication.

[depend]
security

[lib]
lib/jetty-openid-${jetty.version}.jar
lib/jetty-util-ajax-${jetty.version}.jar

[files]
basehome:modules/openid/openid-baseloginservice.xml|etc/openid-baseloginservice.xml

[xml]
etc/openid-baseloginservice.xml
etc/jetty-openid.xml

[ini-template]
## Identity Provider
# jetty.openid.identityProvider=https://accounts.google.com/

## Client ID
# jetty.openid.clientId=1051168419525-5nl60mkugb77p9j194mrh287p1e0ahfi.apps.googleusercontent.com

## Client Secret
# jetty.openid.clientSecret=XT_MIsSv_aUCGollauCaJY8S

## Scopes
# jetty.openid.scopes=email,profile
