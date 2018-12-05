import org.codehaus.groovy.util.ReleaseInfo

def call(body) {

	final JAVA_TOOL_NAME = 'JDK 1.8'
	final MAVEN_TOOL_NAME = 'Maven-3.5.4'
	final SSH_CONFIG_NAME = 'SDQ Webserver Eclipse Update Sites'
	final GIT_BRANCH_PATTERN = 'master'

	def config = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = config
	body()

	node {
		def mandatoryParameters = ['gitUrl', 'webserverDir', 'updateSiteLocation']
		for (mandatoryParameter in mandatoryParameters) {
			if (!config.containsKey(mandatoryParameter) || config.get(mandatoryParameter).toString().trim().isEmpty()) {
				error "Missing mandatory parameter $mandatoryParameter"
			}			
		}

		boolean skipCodeQuality = config.containsKey('skipCodeQuality') && config.get('skipCodeQuality').toString().trim().toBoolean()
		boolean skipNotification = config.containsKey('skipNotification') && config.get('skipNotification').toString().trim().toBoolean()

		boolean doReleaseBuild = params.DO_RELEASE_BUILD.toString().toBoolean()
		String releaseVersion = params.RELEASE_VERSION		
		if (doReleaseBuild && (releaseVersion == null || releaseVersion.trim().isEmpty())) {
			error 'A release build requires a proper release version.'
		}

		if (doReleaseBuild) {
			currentBuild.rawBuild.keepLog(true)
		}

		deleteDir()

		try {
			stage ('Clone') {
				git (
					url: "${config.gitUrl}",
					branch: GIT_BRANCH_PATTERN
					)
			}
			stage ('Build') {
				withEnv([
					"JAVA_HOME=${tool JAVA_TOOL_NAME}",
					"PATH=${tool JAVA_TOOL_NAME}/bin:${env.PATH}"
				]) {
					withMaven(maven: MAVEN_TOOL_NAME) {
						genericSh "mvn clean verify"
					}
				}
			}
			stage ('Deploy') {
				
			}
			stage ('Archive') {
	
			}
			if (!skipCodeQuality) {
				stage ('QualityMetrics') {
					checkstyle([
						pattern: '**/target/checkstyle-result.xml'
					])
					junit([
						testResults: '**/surefire-reports/*.xml',
						allowEmptyResults: true
					])
					jacoco([
						execPattern: '**/target/*.exec',
						classPattern: '**/target/classes',
						sourcePattern: '**/src,**/src-gen,**/xtend-gen',
						inclusionPattern: '**/*.class',
						exclusionPattern: '**/*Test*.class'
					])
				}
			}
		} catch (err) {
			currentBuild.result = 'FAILURE'
			if (err instanceof hudson.AbortException && err.getMessage().contains('script returned exit code 143')) {
				currentBuild.result = 'ABORTED'
			}
			if (err instanceof org.jenkinsci.plugins.workflow.steps.FlowInterruptedException && err.causes.size() == 0) {
				currentBuild.result = 'ABORTED'
			}
			throw err
		} finally {
			def currentResult = currentBuild.result ?: 'SUCCESS'
			def previousResult = currentBuild.previousBuild?.result ?: 'SUCCESS'

			if (!skipNotification) {
				if (currentResult == 'FAILURE') {
					notifyFailure()
				} else if (currentResult == 'SUCCESS' && previouslyFailed()) {
					notifyFix()
				}
			}
		}
	}
}

def genericSh(cmd) {
	if (isUnix()) {
		sh cmd
	}
	else {
		bat cmd
	}
}

def notifyFix() {
	notify('FIXED', 'fixed previous build errors')
}

def notifyFailure() {
	notify('FAILED', 'failed')
}

def notify(token, verb) {
	mail([
		subject: "${token}: build of ${JOB_NAME} #${BUILD_NUMBER}",
		body: "The build of ${JOB_NAME} #${BUILD_NUMBER} ${verb}.\nPlease visit ${BUILD_URL} for details.",
		to: new String('cGFsbGFkaW8tYnVpbGRAaXJhLnVuaS1rYXJsc3J1aGUuZGU='.decodeBase64())
	])
}

def previouslyFailed() {
	for (buildObject = currentBuild.previousBuild; buildObject != null; buildObject = buildObject.previousBuild) {
		if (buildObject.result == null) {
			continue
		}
		if (buildObject.result == 'FAILURE') {
			return true
		}
		if (buildObject.resultIsBetterOrEqualTo('UNSTABLE')) {
			return false
		}
	}
}