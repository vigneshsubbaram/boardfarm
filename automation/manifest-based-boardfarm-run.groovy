pipeline {
	agent { label 'boardfarm && ' + location }

	stages {
		stage('checkout gerrit change') {

			steps {
				sshagent ( [ ssh_auth ] ) {
					script {
						sh "rm -rf *"
						sh "repo init -u " + manifest + " && repo sync --force-remove-dirty"
						sh "repo forall -c 'git checkout gerrit/$GERRIT_BRANCH'"
						sh "repo forall -r ^$GERRIT_PROJECT\$ -c 'pwd && git fetch gerrit $GERRIT_REFSPEC && git checkout FETCH_HEAD && git rebase gerrit/$GERRIT_BRANCH'"
						sh "repo manifest -r"
						def changes = sh returnStatus: true, script: "repo diff | diffstat | grep '0 files changed'"
						if (changes == 0) {
							echo "No changes, ending job"
							return
						}
					}
				}
			}
		}

		stage('run bft test') {
			steps {
				ansiColor('xterm') {
					sh '''
					cd boardfarm
					pwd
					ls
					rm -rf venv
					virtualenv venv
					. venv/bin/activate
					pip install -e .
					repo forall -c '[ -e "requirements.txt" ] && { pip install -r requirements.txt || echo failed; } || true '
					export BFT_OVERLAY="'''+ overlay + '''"
					export BFT_CONFIG=''' + config + '''
					${WORKSPACE}/boardfarm/scripts/whatchanged.py --debug m/master HEAD ${BFT_OVERLAY} ${WORKSPACE}/boardfarm
					export changes_args="`${WORKSPACE}/boardfarm/scripts/whatchanged.py m/master HEAD ${BFT_OVERLAY} ${WORKSPACE}/boardfarm`"
					yes | BFT_DEBUG=y ./bft -b ''' + board + ''' ${changes_args}'''

					sh 'grep tests_fail...0, boardfarm/results/test_results.json'
				}
			}
		}
		stage('post results to gerrit') {
			steps {
				sh '''#!/bin/bash
				cat boardfarm/results/test_results.json | jq '.test_results[] | [ .grade, .name, .message, .elapsed_time ] | @tsv' | \
				sed -e 's/"//g' -e 's/\\t/    /g' | \
				while read -r line; do
				echo $line >> message
				done
				'''
			}
		}
	}
	post {
		always {
			archiveArtifacts artifacts: 'boardfarm/results/*'
			sh 'rm -rf boardfarm/results'
		}
	}
}