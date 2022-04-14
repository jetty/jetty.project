#!groovy

pipeline {
  agent { node { label 'linux' } }
  // save some io during the build
  options { durabilityHint('PERFORMANCE_OPTIMIZED') }
  stages {
    stage("Parallel Stage") {
      timeout(time: 240, unit: 'MINUTES') {
        stage("Build / Test - JDK17 - build") {
          container('jetty-build') {
            steps {
              mavenBuild("jdk17", "clean install -f build", "maven3")
            }
          }
        }
        stage("Build / Test - JDK17 - core") {
          container('jetty-build') {
            steps {
              mavenBuild("jdk17", "clean install -f core", "maven3")
            }
          }
        }
      }
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
                [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn --no-transfer-progress -s $GLOBAL_MVN_SETTINGS -Dmaven.repo.local=.repository -Pci --show-version --batch-mode --errors -Djetty.testtracker.log=true -Dmaven.test.failure.ignore=true $cmdline"
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
    }
  }
}

// vim: et:ts=2:sw=2:ft=groovy
