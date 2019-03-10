import hudson.model.User

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def UniqueBuildIdentifier = env.BUILD_TAG
    def MavenContainerName = "MyMavenContainer_" + UniqueBuildIdentifier
    def TmpBuildFiles = "/var/buildfiles/" + UniqueBuildIdentifier
    def BuildFilesFolder = "BuildResult_" + UniqueBuildIdentifier

    node {
        sh "mkdir $TmpBuildFiles"
    }

    def doDeploy = false
    def doRelease = false
    def releaseVersion = ""
    def commitEmail = ""
    def committer = ""
    def currentBranch = ""

    try {
        pipeline {
            agent any

            parameters {
                booleanParam (name: 'Release', defaultValue: false, description: 'Set true for Release')
                string (defaultValue: '0.0.0', description: 'set Version of Release', name: 'ReleaseVersion', trim: true)
            }

            environment {
                GIT_COMMIT_EMAIL = """${sh(returnStdout: true,script: 'git --no-pager show -s --format=\'%ae\'')}""".trim()
                GIT_COMMITTER = """${sh(returnStdout: true,script: 'git --no-pager show -s --format=\'%an\'')}""".trim()
            }

            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            stages {
                stage('set_Parameter') {
                    steps {
                        script {
                            doRelease = params.Release
                            releaseVersion = params.ReleaseVersion
                            commitEmail = env.GIT_COMMIT_EMAIL
                            committer = env. GIT_COMMITTER
                            currentBranch = env.GIT_BRANCH
                        }
                    }
                } 			    
                stage('Build_Master') {
                    agent {
                        docker {
                            image 'custom_maven:latest'
                            args "-v /media/docker2/$UniqueBuildIdentifier/:/home/jenkinsbuild/buildfiles -v /media/data/empty_maven_folder/:/root/.m2:ro -v /media/data/m2-cache/:/home/jenkinsbuild/tmp_cache -m 4G --storage-opt size=20G --network proxy --name $MavenContainerName"
                        }
                    }
                    when {
                        expression {
                            if (env.CHANGE_TARGET) {    // check pull request
                                return false
                            }
                            return env.GIT_BRANCH == 'master'
                        }
                    }
                    stages {
                        stage('load_cache') {
                            steps {
                                sh 'mkdir /home/jenkinsbuild/.m2/'
                                sh 'cp -r /home/jenkinsbuild/tmp_cache/. /home/jenkinsbuild/.m2/'
                            }
                        }
                        stage('build') {
                            steps {
                                sh 'mvn clean verify'
                                script {
                                    doDeploy = true
                                }
                            }
                        }
                        stage('save_buildfiles') {
                            steps {
                                script {
                                    def MavenPwd = sh (
                                        script: 'pwd',
                                        returnStdout: true
                                    ).trim()
                                    sh "cp -r $MavenPwd/. /home/jenkinsbuild/buildfiles/"
                                }
                            }
                        }
                        stage('save_cache') {
                            steps {
                                sh 'cp -r /home/jenkinsbuild/.m2/. /home/jenkinsbuild/tmp_cache/'
                            }
                        }
                    }
                }

                stage('Build_Slave') {
                    agent {
                        docker {
                            image 'custom_maven:latest'
                            args '-v /media/data/empty_maven_folder/:/root/.m2:ro -v /media/data/m2-cache/:/home/jenkinsbuild/tmp_cache:ro -m 4G --storage-opt size=20G --network proxy'
                        }
                    }
                    when {
                        expression {
                            if (env.CHANGE_TARGET) {
                                return true
                            }
                            return !(env.GIT_BRANCH == 'master')
                        }
                    }
                    stages {
                        stage('load_cache') {
                            steps {
                                sh 'mkdir /home/jenkinsbuild/.m2/'
                                sh 'cp -r /home/jenkinsbuild/tmp_cache/. /home/jenkinsbuild/.m2/'
                            }
                        }
                        stage('build') {
                            steps {
                                sh 'mvn clean verify'
                            }
                        }
                    }
                }
            }
        }

        node {
            sh "echo 'Build finished. Start post processing'"
            if(doDeploy) {
                postProcessBuildResults(config, BuildFilesFolder, MavenContainerName, doRelease, releaseVersion, TmpBuildFiles)
            }
            sendEmailNotification(commitEmail, committer, currentBranch)
            sh "rm -rf $TmpBuildFiles"
        }
        
    } catch (err) {
        node {
            sh "rm -rf $TmpBuildFiles"
            sh "echo 'An error occured during the build!'"
            String errMsg = err.getMessage()
            sh "echo $errMsg"
            currentBuild.result = 'FAILURE'
            sendEmailNotification(commitEmail, committer, currentBranch)
        }
    }
}

def postProcessBuildResults(config, BuildFilesFolder, MavenContainerName, doReleaseBuild, releaseVersion, TmpBuildFiles) {
    node {
        def mandatoryParameters = ['sshConfigName', 'absoluteWebserverDir', 'webserverDir', 'updateSiteLocation']
        for (mandatoryParameter in mandatoryParameters) {
            if (!config.containsKey(mandatoryParameter) || config.get(mandatoryParameter).toString().trim().isEmpty()) {
                error "Missing mandatory parameter $mandatoryParameter"
            }
        }
        
        if (doReleaseBuild && releaseVersion == "") {
            error "To do a release build it is mandatory to specify a release version"
        }

        boolean skipCodeQuality = config.containsKey('skipCodeQuality') && config.get('skipCodeQuality').toString().trim().toBoolean()
        boolean skipNotification = config.containsKey('skipNotification') && config.get('skipNotification').toString().trim().toBoolean()

        String usl = "$BuildFilesFolder/${config.updateSiteLocation}"

        sh "mkdir $BuildFilesFolder"
        sh "cp -r $TmpBuildFiles/. $BuildFilesFolder/"

        try {
            // deploy:
            sshPublisher(
                failOnError: true,
                publishers: [
                    sshPublisherDesc(
                        configName: "${config.sshConfigName}",
                        transfers: [
                            sshTransfer(
                                sourceFiles: "$usl/**/*",
                                cleanRemote: true,
                                removePrefix: "$usl",
                                remoteDirectory: "${config.webserverDir}/nightly"
                            )
                        ]
                    )
                ]
            )
            if (doReleaseBuild) {
                sshPublisher(
                    failOnError: true,
                    publishers: [
                        sshPublisherDesc(
                            configName: "${config.sshConfigName}",
                            transfers: [
                                sshTransfer(
                                    execCommand:
                                    "rm -rf ${config.absoluteWebserverDir}/${config.webserverDir}/releases/latest &&" +
                                    "rm -rf ${config.absoluteWebserverDir}/${config.webserverDir}/releases/$releaseVersion &&" +
                                    "mkdir -p ${config.absoluteWebserverDir}/${config.webserverDir}/releases/$releaseVersion &&" +
                                    "cp -a ${config.absoluteWebserverDir}/${config.webserverDir}/nightly/* ${config.absoluteWebserverDir}/${config.webserverDir}/releases/$releaseVersion/ &&" +
                                    "ln -s ${config.absoluteWebserverDir}/${config.webserverDir}/releases/$releaseVersion ${config.absoluteWebserverDir}/${config.webserverDir}/releases/latest"
                                )
                            ]
                        )
                    ]
                )
            }

            // archive:
            archiveArtifacts "$usl/**/*"

            if (!skipCodeQuality) {
                sh "echo 'Publish JavaDoc'"
                publishHTML([
                    allowMissing: false,
                    alwaysLinkToLastBuild: false,
                    keepAll: false,
                    reportDir: "$usl/javadoc",
                    reportFiles: 'overview-summary.html',
                    reportName: 'JavaDoc',
                    reportTitles: ''
                ])

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
            else {
                sh "echo 'Skip JavaDoc and CodeQuality'"
            }

        } catch (err) {
            sh "rm -rf $BuildFilesFolder"
            sh "echo 'An error occured during post processing!'"
            String errMsg = err.getMessage()
            sh "echo $errMsg"
            currentBuild.result = 'FAILURE'
            if (err instanceof hudson.AbortException && err.getMessage().contains('script returned exit code 143')) {
                currentBuild.result = 'ABORTED'
            }
            if (err instanceof org.jenkinsci.plugins.workflow.steps.FlowInterruptedException && err.causes.size() == 0) {
                currentBuild.result = 'ABORTED'
            }
            throw err
        }

        sh "rm -rf $BuildFilesFolder"
        sh "echo 'Post processing finished.'"
    }
}
def sendEmailNotification (commitEmail, committer, branch) {
    sh "echo 'Send mail notification.'"
	def currentResult = currentBuild.result ?: 'SUCCESS'
	def previousResult = currentBuild.previousBuild?.result ?: 'SUCCESS'
	def recipientsMail = ''
	def userJenkins = User.getById(committer ,false)
	
	if(userJenkins != null) {
		commitEmail = userJenkins.getProperty(hudson.tasks.Mailer.UserProperty.class).getAddress()
	}
	
	if(branch == 'master') {
		recipientsMail = commitEmail + '; $DEFAULT_RECIPIENTS'	
	} else {
		recipientsMail = commitEmail
	}

	if (currentResult == 'FAILURE') {
		notify('FAILED', recipientsMail, 'failed')
	} else if (currentResult == 'SUCCESS' && previouslyFailed()) {
		notify('FIXED', recipientsMail, 'fixed previous build error')
	}
}

def notify (token, recipients, verb) {
	emailext body: "The build of ${JOB_NAME} #${BUILD_NUMBER} ${verb}.\nPlease visit ${BUILD_URL} for details.",
		to: recipients,
		subject: "${token}: build of ${JOB_NAME} #${BUILD_NUMBER}"	
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
