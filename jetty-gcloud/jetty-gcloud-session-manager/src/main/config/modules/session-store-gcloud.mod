[description]
Enables GCloudDatastore session management.

[tags]
session
gcloud

[provides]
session-store

[depends]
gcloud-datastore
annotations
webapp
sessions

[files]
basehome:modules/gcloud/index.yaml|etc/index.yaml

[lib]
lib/jetty-gcloud-session-manager-${jetty.version}.jar

[xml]
etc/sessions/gcloud/session-store.xml

[ini-template]

## GCloudDatastore Session config
#jetty.session.gcloud.maxRetries=5
#jetty.session.gcloud.backoffMs=1000
#jetty.session.gcloud.namespace=
#jetty.session.gcloud.model.kind=GCloudSession
#jetty.session.gcloud.model.id=id
#jetty.session.gcloud.model.contextPath=contextPath
#jetty.session.gcloud.model.vhost=vhost
#jetty.session.gcloud.model.accessed=accessed
#jetty.session.gcloud.model.lastAccessed=lastAccessed
#jetty.session.gcloud.model.createTime=createTime
#jetty.session.gcloud.model.cookieSetTime=cookieSetTime
#jetty.session.gcloud.model.lastNode=lastNode
#jetty.session.gcloud.model.expiry=expiry
#jetty.session.gcloud.model.maxInactive=maxInactive
#jetty.session.gcloud.model.attributes=attributes

