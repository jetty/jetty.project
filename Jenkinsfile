#!groovy

pipeline {
  agent none
  // save some io during the build
  options {
    skipDefaultCheckout()
    durabilityHint('MAX_SURVIVABILITY')
    //buildDiscarder logRotator( numToKeepStr: '60' )
    disableRestartFromStage()
  }
  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test - JDK21") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              script{
                properties([buildDiscarder(logRotator(artifactNumToKeepStr: '5', numToKeepStr: env.BRANCH_NAME=='jetty-12.0.x'?'60':'5'))])
              }
              checkout scm
              mavenBuild( "jdk21", "clean install -Dspotbugs.skip=true -Djacoco.skip=true", "maven3")
              recordIssues id: "jdk21", name: "Static Analysis jdk21", aggregatingResults: true, enabledForFailure: true,
                            tools: [mavenConsole(), java(), javaDoc()],
                            skipPublishingChecks: true, skipBlames: true
            }
          }
        }

        stage("Build / Test - JDK24") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk24", "clean install -Dspotbugs.skip=true -Djacoco.skip=true", "maven3")
              recordIssues id: "jdk24", name: "Static Analysis jdk24", aggregatingResults: true, enabledForFailure: true, tools: [mavenConsole(), java(), javaDoc()]
            }
          }
        }

        stage("Build / Test - JDK17 Javadoc") {
          agent { node { label 'linux-light' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              withEnv(["JAVA_HOME=${ tool 'jdk17' }",
                       "PATH+MAVEN=${ tool 'jdk17' }/bin:${tool 'maven3'}/bin",
                       "MAVEN_OPTS=-Xms3072m -Xmx5120m -Djava.awt.headless=true -client -XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=100"]) {
                configFileProvider(
                        [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS'),
                         configFile(fileId: 'maven-build-cache-config.xml', variable: 'MVN_BUILD_CACHE_CONFIG')]) {
                  sh "mvn -s $GLOBAL_MVN_SETTINGS clean install -DskipTests javadoc:aggregate -B -Pjavadoc-aggregate"
                }
              }
            }
          }
        }

        stage("Build / Test - JDK17") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk17", "clean install -Dspotbugs.skip=true", "maven3") // javadoc:javadoc
              recordIssues id: "analysis-jdk17", name: "Static Analysis jdk17", aggregatingResults: true, enabledForFailure: true,
                            tools: [mavenConsole(), java(), javaDoc()],
                            skipPublishingChecks: true, skipBlames: true
              recordCoverage id: "coverage-jdk17", name: "Coverage jdk17",
                             tools: [[parser: 'JACOCO'], [parser: 'JUNIT', pattern: '**/target/surefire-reports/**/TEST*.xml,**/target/invoker-reports/TEST*.xml']],
                             sourceCodeRetention: 'MODIFIED',
                             sourceDirectories: [[path: 'src/main/java'], [path: 'target/generated-sources/ee8']]
            }
          }
        }
      }
    }
  }
  post {
    failure {
      slackNotif()
    }
    unstable {
      slackNotif()
    }
    fixed {
      slackNotif()
      websiteBuild()
    }
    success {
      websiteBuild()
    }
  }
}

def slackNotif() {
  script {
    try {
      if ( env.BRANCH_NAME == 'jetty-10.0.x' || env.BRANCH_NAME == 'jetty-11.0.x' || env.BRANCH_NAME == 'jetty-12.0.x' ) {
        //BUILD_USER = currentBuild.rawBuild.getCause(Cause.UserIdCause).getUserId()
        // by ${BUILD_USER}
        COLOR_MAP = ['SUCCESS': 'good', 'FAILURE': 'danger', 'UNSTABLE': 'danger', 'ABORTED': 'danger']
        slackSend channel: '#jenkins',
                  color: COLOR_MAP[currentBuild.currentResult],
                  message: "*${currentBuild.currentResult}:* Job ${env.JOB_NAME} build ${env.BUILD_NUMBER} - ${env.BUILD_URL}"
      }
    } catch (Exception e) {
      e.printStackTrace()
      echo "skip failure slack notification: " + e.getMessage()
    }
  }
}

/**
 * To other developers, if you are using this method above, please use the following syntax.
 *
 * mavenBuild("<jdk>", "<profiles> <goals> <plugins> <properties>"
 *
 * @param jdk the jdk tool name (in jenkins) to use for this build
 * @param cmdline the command line in "<profiles> <goals> <properties>"`format.
 * @return the Jenkinsfile step representing a maven build
 */
def mavenBuild(jdk, cmdline, mvnName) {
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${ tool "$jdk" }/bin:${tool "$mvnName"}/bin",
               "MAVEN_OPTS=-Xms3072m -Xmx5120m -Djava.awt.headless=true -client -XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=100"]) {
      configFileProvider(
        [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS'),
          configFile(fileId: 'maven-build-cache-config.xml', variable: 'MVN_BUILD_CACHE_CONFIG')]) {
          //sh "cp $MVN_BUILD_CACHE_CONFIG .mvn/maven-build-cache-config.xml"
          //-Dmaven.build.cache.configPath=$MVN_BUILD_CACHE_CONFIG
          buildCache = useBuildCache()
          if (buildCache) {
            echo "Using build cache"
            extraArgs = " -Dmaven.build.cache.restoreGeneratedSources=false -Dmaven.build.cache.remote.url=http://nexus-service.nexus.svc.cluster.local:8081/repository/maven-build-cache -Dmaven.build.cache.remote.enabled=true -Dmaven.build.cache.remote.save.enabled=true -Dmaven.build.cache.remote.server.id=nexus-cred "
          } else {
            // when not using cache
            echo "Not using build cache"
            extraArgs = " -Dmaven.test.failure.ignore=true -Dmaven.build.cache.skipCache=true -Dmaven.build.cache.remote.url=http://nexus-service.nexus.svc.cluster.local:8081/repository/maven-build-cache -Dmaven.build.cache.remote.enabled=true -Dmaven.build.cache.remote.save.enabled=true -Dmaven.build.cache.remote.server.id=nexus-cred "
          }
          if (env.BRANCH_NAME ==~ /PR-\d+/) {
            if (pullRequest.labels.contains("build-all-tests")) {
              extraArgs = " -Dmaven.test.failure.ignore=true "
            }
          }
          dashProfile = ""
          if(useEclipseDash()) {
            dashProfile = " -Peclipse-dash "
          }
          sh "mkdir ~/.mimir"
          sh "cp jenkins-mimir-daemon.properties ~/.mimir/daemon.properties"
          sh "cat ~/.mimir/daemon.properties"
          sh "rm -rf .repository"
          sh "mvn $extraArgs $dashProfile -s $GLOBAL_MVN_SETTINGS -DsettingsPath=$GLOBAL_MVN_SETTINGS -Dmaven.repo.uri=http://nexus-service.nexus.svc.cluster.local:8081/repository/maven-public/ -ntp -Dmaven.repo.local=.repository -Pci -V -B -e -U $cmdline"
          if(saveHome()) {
            archiveArtifacts artifacts: ".repository/org/eclipse/jetty/jetty-home/**/jetty-home-*", allowEmptyArchive: true, onlyIfSuccessful: false
          }
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/TEST**.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
      // temporary logs to remove
      sh "ls -lrt ~/.mimir/"
    }
  }
}

/**
 * calculate to use cache or not. per default will not run
 */
def useBuildCache() {
  def labelNoBuildCache = false
  if (env.BRANCH_NAME ==~ /PR-\d+/) {
    labelNoBuildCache = pullRequest.labels.contains("build-no-cache") || pullRequest.labels.contains("dependencies")
  }
  def noBuildCache = (env.BRANCH_NAME == 'jetty-12.0.x') || labelNoBuildCache;
  return !noBuildCache;
  // want to skip build cache
  // return false
}

def useEclipseDash() {
  if (env.BRANCH_NAME ==~ /PR-\d+/) {
    return pullRequest.labels.contains("eclipse-dash")
  }
  return false
}

def saveHome() {
  if (env.BRANCH_NAME ==~ /PR-\d+/) {
    return pullRequest.labels.contains("save-home")
  }
  return false;
}

def websiteBuild() {
  script {
    try {
      if (env.BRANCH_NAME == 'jetty-10.0.x' || env.BRANCH_NAME == 'jetty-11.0.x' || env.BRANCH_NAME == 'jetty-12.0.x') {
        build(job: 'website/jetty.website/main', propagate: false, wait: false)
      }
    } catch (Exception e) {
      e.printStackTrace()
      echo "skip website build triggering: " + e.getMessage()
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
