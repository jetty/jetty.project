#!groovy

node('linux') {
  // System Dependent Locations
  def mvntool = tool name: 'maven3', type: 'hudson.tasks.Maven$MavenInstallation'
  def jdktool = tool name: 'jdk7', type: 'hudson.model.JDK'

  // Environment
  List mvnEnv = ["PATH+MVN=${mvntool}/bin", "PATH+JDK=${jdktool}/bin", "JAVA_HOME=${jdktool}/", "MAVEN_HOME=${mvntool}"]
  mvnEnv.add("MAVEN_OPTS=-Xms256m -Xmx1024m -XX:MaxPermSize=512m -Djava.awt.headless=true")

  try
  {
    stage 'Checkout'
    checkout scm
  } catch (Exception e) {
    notifyBuild("Checkout Failure")
    throw e
  }

  try
  {
    stage 'Compile'
    withEnv(mvnEnv) {
      timeout(15) {
        sh "mvn -B clean install -Dtest=None"
      }
    }
  } catch(Exception e) {
    notifyBuild("Compile Failure")
    throw e
  }

  try
  {
    stage 'Javadoc'
    withEnv(mvnEnv) {
      timeout(15) {
        sh "mvn -B javadoc:javadoc"
      }
    }
  } catch(Exception e) {
    notifyBuild("Javadoc Failure")
    throw e
  }

  try
  {
    stage 'Test'
    withEnv(mvnEnv) {
      timeout(60) {
        // Run test phase / ignore test failures
        sh "mvn -B install -Dmaven.test.failure.ignore=true"
        // Report failures in the jenkins UI
        step([$class: 'JUnitResultArchiver', 
            testResults: '**/target/surefire-reports/TEST-*.xml'])
        // Collect up the jacoco execution results
        step([$class: 'JacocoPublisher', 
            execPattern:'target/jacoco*.exec', 
            classPattern: 'target/classes', 
            sourcePattern: 'src/main/java'])
      }
      if(isUnstable())
      {
        notifyBuild("Unstable / Test Errors")
      }
    }
  } catch(Exception e) {
    notifyBuild("Test Failure")
    throw e
  }
}

// True if this build is part of the "active" branches
// for Jetty.
def isActiveBranch()
{
  def branchName = "${env.BRANCH_NAME}"
  return ( branchName == "master" ||
           branchName.startsWith("jetty-") );
}

// Test if the Jenkins Pipeline or Step has marked the
// current build as unstable
def isUnstable()
{
  return currentBuild.result == "UNSTABLE"
}

// Send a notification about the build status
def notifyBuild(String buildStatus)
{
  if ( !isActiveBranch() )
  {
    // don't send notifications on transient branches
    return
  }

  // default the value
  buildStatus = buildStatus ?: "UNKNOWN"

  def email = "${env.EMAILADDRESS}"
  def summary = "${env.JOB_NAME}#${env.BUILD_NUMBER} - ${buildStatus}"
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
