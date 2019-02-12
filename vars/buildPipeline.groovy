//import org.codehaus.groovy.util.ReleaseInfo

def call() {
    final exx = this
    node {
        def outp = sh (
            script: 'printenv',
            returnStdout: true
        ).trim()
        echo outp
    }
    execute_command(this)
    execute_command(exx)
    
    pipeline {
		/*agent any
		environment {
			workspaceMaster = ''
			workspaceSlave = ''
		}*/
        agent none

		options {
			timeout(time: 30, unit: 'MINUTES')
		}
		stages {
			stage('Build_Master') {
				agent {
					docker {
						image 'custom_maven:latest'
						args '-v /media/data/empty_maven_folder/:/root/.m2:ro -v /media/data/m2-cache/:/home/jenkinsbuild/tmp_cache -m 4G --storage-opt size=20G --network proxy'
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
                            execute_command(exx)
							sh 'mkdir /home/jenkinsbuild/.m2/'
							sh 'cp -r /home/jenkinsbuild/tmp_cache/. /home/jenkinsbuild/.m2/'
						}
					}
					stage('build') {
						steps {
							sh 'mvn clean verify'
						}
					}
					stage('save_cache') {
						steps {
							sh 'cp -r /home/jenkinsbuild/.m2/. /home/jenkinsbuild/tmp_cache/'
						}
					}
				}
				post {
					always {
						script {
							workspaceMaster = env.WORKSPACE	
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
				post {
					always {
						script {
							workspaceSlave = env.WORKSPACE							
						}
					}
				}					
			}					
		}
        /*post {
			always {				
				script {
					cleanWorkspace("${env.WORKSPACE}")
					if(env.GIT_BRANCH == 'master') {
						cleanWorkspace(workspaceMaster)
					} else {
						cleanWorkspace(workspaceSlave)
					}
				}
			}
		}*/	
    }
}

def execute_command(ex_env) {
    ex_env.node {
        ex_env.sh 'docker ps'
    }
}
/*def cleanWorkspace(workspaceDir) {
	dir(workspaceDir) {
	  deleteDir()
	}
	dir(workspaceDir + "@tmp") {
	  deleteDir()
	}
	dir(workspaceDir + "@script") {
	  deleteDir()
	}
	dir(workspaceDir + "@script@tmp") {
	  deleteDir()
	}
}*/
