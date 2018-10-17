#!groovy

// in case of change update method isMainBuild
def jdks = ["jdk8","jdk9","jdk10","jdk11"]
def oss = ["linux"]
def builds = [:]
for (def os in oss) {
  for (def jdk in jdks) {
    builds[os+"_"+jdk] = getFullBuild( jdk, os )
  }
}

parallel builds


def getFullBuild(jdk, os) {
  return {
    node(os) {
      // System Dependent Locations
      def mvnName = 'maven3.5'
      def localRepo = "${env.JENKINS_HOME}/${env.EXECUTOR_NUMBER}" // ".repository" //
      def settingsName = 'oss-settings.xml'
      def mavenOpts = '-Xms1g -Xmx4g -Djava.awt.headless=true'

      try {
        stage("Build ${jdk}/${os}") {
          timeout(time: 90, unit: 'MINUTES') {
            // Run test phase / ignore test failures
            checkout scm
            withMaven(
                    maven: mvnName,
                    jdk: "$jdk",
                    publisherStrategy: 'EXPLICIT',
                    globalMavenSettingsConfig: settingsName,
                    //options: [invokerPublisher(disabled: false)],
                    mavenOpts: mavenOpts,
                    mavenLocalRepo: localRepo) {
              sh "mvn -V -B install -Dmaven.test.failure.ignore=true -e -Pmongodb -Powasp -T3 -Djetty.testtracker.log=true -Dunix.socket.tmp="+env.JENKINS_HOME
              sh "mvn -V -B javadoc:javadoc -T6 -e"
            }
            // withMaven doesn't label..
            // Report failures in the jenkins UI
            junit testResults:'**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml'
            consoleParsers = [[parserName: 'JavaDoc'],
                              [parserName: 'JavaC']];
            if (isMainBuild( jdk )) {
              // Collect up the jacoco execution results
              def jacocoExcludes =
                      // build tools
                      "**/org/eclipse/jetty/ant/**" + ",**/org/eclipse/jetty/maven/**" +
                              ",**/org/eclipse/jetty/jspc/**" +
                              // example code / documentation
                              ",**/org/eclipse/jetty/embedded/**" + ",**/org/eclipse/jetty/asyncrest/**" +
                              ",**/org/eclipse/jetty/demo/**" +
                              // special environments / late integrations
                              ",**/org/eclipse/jetty/gcloud/**" + ",**/org/eclipse/jetty/infinispan/**" +
                              ",**/org/eclipse/jetty/osgi/**" + ",**/org/eclipse/jetty/spring/**" +
                              ",**/org/eclipse/jetty/http/spi/**" +
                              // test classes
                              ",**/org/eclipse/jetty/tests/**" + ",**/org/eclipse/jetty/test/**";
              jacoco inclusionPattern: '**/org/eclipse/jetty/**/*.class',
                     exclusionPattern: jacocoExcludes,
                     execPattern     : '**/target/jacoco.exec',
                     classPattern    : '**/target/classes',
                     sourcePattern   : '**/src/main/java'
              consoleParsers = [[parserName: 'Maven'],
                                [parserName: 'JavaDoc'],
                                [parserName: 'JavaC']];
              step([$class: 'MavenInvokerRecorder', reportsFilenamePattern: "**/target/invoker-reports/BUILD*.xml",
                    invokerBuildDir: "**/target/its"])
            }

            // Report on Maven and Javadoc warnings
            step( [$class        : 'WarningsPublisher',
                   consoleParsers: consoleParsers] )
          }
          if(isUnstable()) {
            notifyBuild("Unstable / Test Errors", jdk)
          }
        }
      } catch(Exception e) {
        notifyBuild("Test Failure", jdk)
        throw e
      }

      try
      {
        stage ("Compact3 - ${jdk}") {
          withMaven(
                  maven: mvnName,
                  jdk: "$jdk",
                  publisherStrategy: 'EXPLICIT',
                  globalMavenSettingsConfig: settingsName,
                  mavenOpts: mavenOpts,
                  mavenLocalRepo: localRepo) {
            sh "mvn -f aggregates/jetty-all-compact3 -V -B -Pcompact3 clean install -T6"
          }
        }
      } catch(Exception e) {
        notifyBuild("Compact3 Failure", jdk)
        throw e
      }

    }
  }
}

def isMainBuild(jdk) {
  return jdk == "jdk8"
}


// True if this build is part of the "active" branches
// for Jetty.
def isActiveBranch() {
  def branchName = "${env.BRANCH_NAME}"
  return ( branchName == "master" ||
          ( branchName.startsWith("jetty-") && branchName.endsWith(".x") ) );
}

// Test if the Jenkins Pipeline or Step has marked the
// current build as unstable
def isUnstable() {
  return currentBuild.result == "UNSTABLE"
}

// Send a notification about the build status
def notifyBuild(String buildStatus, String jdk) {
  if ( !isActiveBranch() ) {
    // don't send notifications on transient branches
    return
  }

  // default the value
  buildStatus = buildStatus ?: "UNKNOWN"

  def email = "${env.EMAILADDRESS}"
  def summary = "${env.JOB_NAME}#${env.BUILD_NUMBER} - ${buildStatus} with jdk ${jdk}"
  def detail = """<h4>Job: <a href='${env.JOB_URL}'>${env.JOB_NAME}</a> [#${env.BUILD_NUMBER}]</h4>
    <p><b>${buildStatus}</b></p>
    <table>
      <tr><td>Build</td><td><a href='${env.BUILD_URL}'>${env.BUILD_URL}</a></td><tr>
      <tr><td>Console</td><td><a href='${env.BUILD_URL}console'>${env.BUILD_URL}console</a></td><tr>
      <tr><td>Test Report</td><td><a href='${env.BUILD_URL}testReport/'>${env.BUILD_URL}testReport/</a></td><tr>
    </table>
    """

  emailext (
          to: email,
          subject: summary,
          body: detail
  )
}

// vim: et:ts=2:sw=2:ft=groovy
