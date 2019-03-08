//import org.codehaus.groovy.util.ReleaseInfo
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
    def MavenPwd = ""

    node {
        sh "mkdir $TmpBuildFiles"
    }

    def tasks = [:]
    def doDeploy = false
    def doRelease = false
    def releaseVersion = ""
    def doPostProcessing = false
    def postProcessingFinished = false
    def commitEmail = ""
    def committer = ""
    def currentBranch = ""

    /*tasks["Jenkins_Container"] = {
        while (!doPostProcessing) {
            sleep(5)
        }
        if(doDeploy) {
            postProcessBuildResults(config, BuildFilesFolder, MavenContainerName, MavenPwd, doRelease, releaseVersion)
        }
        postProcessingFinished = true	
	sendEmailNotification(commitEmail, committer, currentBranch)    
    }*/

    //tasks["Maven_Container"] = {
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
                                    MavenPwd = sh (
                                        script: 'pwd',
                                        returnStdout: true
                                    ).trim()
                                    doDeploy = true
                                    doPostProcessing = true
                                    //while (!postProcessingFinished) {
                                    //    sleep(5)
                                    //}
                                }
                            }
                        }
                        stage('save_buildfiles') {
                            steps {
                                sh "cp -r $pwd/. /home/jenkinsbuild/buildfiles"
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
                                script {
                                    MavenPwd = sh (
                                        script: 'pwd',
                                        returnStdout: true
                                    ).trim()
                                    doPostProcessing = true
                                    while (!postProcessingFinished) {
                                        sleep(5)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            post {
                always {
                    // in case of a failure during the build
                    script {
                        doDeploy = false
                        doPostProcessing = true
                    }
                }
            }
        }
    //}

    if(doDeploy) {
        postProcessBuildResults(config, BuildFilesFolder, MavenContainerName, MavenPwd, doRelease, releaseVersion)
    }
    postProcessingFinished = true	
    sendEmailNotification(commitEmail, committer, currentBranch)    
    //parallel tasks
        
    node {
        sh "rm -rf $TmpBuildFiles"
    }
}

def postProcessBuildResults(config, BuildFilesFolder, MavenContainerName, MavenPwd, doReleaseBuild, releaseVersion) {
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

        sh "echo ${config.sshConfigName}"
        sh "echo ${config.absoluteWebserverDir}"
        sh "echo ${config.webserverDir}"
        sh "echo ${config.updateSiteLocation}"

        String usl = "$BuildFilesFolder/${config.updateSiteLocation}"

        sh "echo $usl"
        MavenPwd = MavenPwd + "/."
        sh "mkdir $BuildFilesFolder"
        //sh "docker cp $MavenContainerName:$MavenPwd $BuildFilesFolder"
        sh "cp -r $TmpBuildFiles $BuildFilesFolder"

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
            sh "echo 'An error occured!'"
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
    }
}
def sendEmailNotification (commitEmail, committer, branch) {
	def currentResult = currentBuild.result ?: 'SUCCESS'
	def previousResult = currentBuild.previousBuild?.result ?: 'SUCCESS'
	def recipientsMail = ''
	def userEmail = User.getById(committer ,false).getProperty(hudson.tasks.Mailer.UserProperty.class).getAddress()
	
	if(userEmail != null) {
		commitEmail = userEmail
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
