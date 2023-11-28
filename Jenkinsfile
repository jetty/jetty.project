#!groovy

pipeline {
  agent none
  // save some io during the build
  options {
    skipDefaultCheckout()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    buildDiscarder logRotator( numToKeepStr: '60' )
    disableRestartFromStage()
  }
  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test - JDK21") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk21", "clean install -Dspotbugs.skip=true -Djacoco.skip=true", "maven3")
              recordIssues id: "jdk21", name: "Static Analysis jdk21", aggregatingResults: true, enabledForFailure: true,
                            tools: [mavenConsole(), java(), checkStyle(), javaDoc()],
                            skipPublishingChecks: true, blameDisabled: true
            }
          }
        }

        stage("Build / Test - JDK17") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk17", "clean install -Perrorprone", "maven3") // javadoc:javadoc
              // Collect up the jacoco execution results (only on main build)
              jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                     exclusionPattern: '' +
                             // build tools
                             '**/org/eclipse/jetty/ant/**' +
                             ',*/org/eclipse/jetty/maven/its/**' +
                             ',**/org/eclipse/jetty/its/**' +
                             // example code / documentation
                             ',**/org/eclipse/jetty/embedded/**' +
                             ',**/org/eclipse/jetty/asyncrest/**' +
                             ',**/org/eclipse/jetty/demo/**' +
                             // special environments / late integrations
                             ',**/org/eclipse/jetty/gcloud/**' +
                             ',**/org/eclipse/jetty/infinispan/**' +
                             ',**/org/eclipse/jetty/osgi/**' +
                             ',**/org/eclipse/jetty/http/spi/**' +
                             // test classes
                             ',**/org/eclipse/jetty/tests/**' +
                             ',**/org/eclipse/jetty/test/**',
                     execPattern: '**/target/jacoco.exec',
                     classPattern: '**/target/classes',
                     sourcePattern: '**/src/main/java'
              recordIssues id: "jdk17", name: "Static Analysis jdk17", aggregatingResults: true, enabledForFailure: true,
                            tools: [mavenConsole(), java(), checkStyle(), errorProne(), spotBugs(), javaDoc()],
                            skipPublishingChecks: true, blameDisabled: true
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
               "MAVEN_OPTS=-Xms2g -Xmx4g -Djava.awt.headless=true"]) {
      configFileProvider(
        [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS'),
          configFile(fileId: 'maven-build-cache-config.xml', variable: 'MVN_BUILD_CACHE_CONFIG')]) {
          //sh "cp $MVN_BUILD_CACHE_CONFIG .mvn/maven-build-cache-config.xml"
          //-Dmaven.build.cache.configPath=$MVN_BUILD_CACHE_CONFIG
          buildCache = useBuildCache()
          if (buildCache) {
          echo "Using build cache"
            extraArgs = " -Dmaven.build.cache.restoreGeneratedSources=false -Dmaven.build.cache.remote.url=http://nginx-cache-service.jenkins.svc.cluster.local:80 -Dmaven.build.cache.remote.enabled=true -Dmaven.build.cache.remote.save.enabled=true -Dmaven.build.cache.remote.server.id=remote-build-cache-server -Daether.connector.http.supportWebDav=true "
          } else {
            // when not using cache
            echo "Not using build cache"
            extraArgs = " -Dmaven.test.failure.ignore=true -Dmaven.build.cache.enabled=false "
          }
          if (env.BRANCH_NAME ==~ /PR-\d+/) {
            if (pullRequest.labels.contains("build-all-tests")) {
              extraArgs = " -Dmaven.test.failure.ignore=true "
            }
          }
          sh "mvn $extraArgs -DsettingsPath=$GLOBAL_MVN_SETTINGS -Dmaven.repo.uri=http://nexus-service.nexus.svc.cluster.local:8081/repository/maven-public/ -ntp -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -Pci -V -B -e -U $cmdline"
          if(saveHome()) {
            archiveArtifacts artifacts: "**/jetty-home/target/jetty-home-*", allowEmptyArchive: true, onlyIfSuccessful: false
          }
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
    }
  }
}

/**
 * calculate to use cache or not. per default will not run
 */
def useBuildCache() {
  def labelNoBuildCache = false
  if (env.BRANCH_NAME ==~ /PR-\d+/) {
    labelNoBuildCache = pullRequest.labels.contains("build-no-cache")
  }
  def noBuildCache = (env.BRANCH_NAME == 'jetty-12.0.x') || labelNoBuildCache;
  return !noBuildCache;
  // want to skip build cache
  // return false
}

def saveHome() {
  if (env.BRANCH_NAME ==~ /PR-\d+/) {
    return pullRequest.labels.contains("save-home")
  }
  return false;
}

// vim: et:ts=2:sw=2:ft=groovy
