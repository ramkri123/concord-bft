import groovy.json.*
import hudson.util.Secret

def call(){
  def agentLabel = "genericVM"
  def genericTests = true
  def additional_components_to_build = ""
  def master_branch_job_name = "Master Branch"
  def lint_test_job_name = "Blockchain LINT Tests"
  def memory_leak_job_name = "BlockchainMemoryLeakTesting"
  def performance_test_job_name = "Blockchain Performance Test"
  def persephone_test_job_name = "Blockchain Persephone Tests"

  if (env.JOB_NAME.contains(memory_leak_job_name)) {
    echo "**** Jenkins job for Memory Leak Test"
    agentLabel = "MemoryLeakTesting"
    genericTests = false
  } else if (env.JOB_NAME.contains(performance_test_job_name)) {
    echo "**** Jenkins job for Performance Test"
    genericTests = false
    additional_components_to_build = additional_components_to_build + "PerformanceTests,"
  } else if (env.JOB_NAME.contains(persephone_test_job_name)) {
    echo "**** Jenkins job for Persephone Test"
    genericTests = false
  } else if (env.JOB_NAME.contains(lint_test_job_name)) {
    echo "**** Jenkins job for LINT Tests"
    genericTests = false
  } else {
    echo "**** Jenkins job for Generic Test Run"
  }

  pipeline {
    agent { label params.jenkins_node ? params.jenkins_node : agentLabel }
    tools {
      // 8.9.4 is the minimum for Truffle.
      nodejs 'Node 8.9.4'
    }
    options{
      gitLabConnection('TheGitlabConnection')
    }
    parameters {
      booleanParam defaultValue: false, description: "Whether to deploy the docker images for production. REQUIRES A VERSION NUMBER IN THE 'version_param' FIELD.", name: "deploy"

      booleanParam defaultValue: true, description: "Whether to run tests.", name: "run_tests"
      password defaultValue: "",
             description: "If this is a run which pushes items to a repo (e.g. Artifactory, Bintray, Dockerhub), and you are bypassing tests, a password is needed.",
             name: "skip_tests_password"

      string defaultValue: "",
             description: "The version number for releases. Used as a tag in DockerHub and Git.  REQUIRED IF THE 'deploy' CHECKBOX IS CHECKED.",
             name: "version_param"

      string defaultValue: "",
             description: "Blockchain commit or branch to use.  Providing a branch name will pull the branch's latest commit.",
             name: "blockchain_branch_or_commit"
      string defaultValue: "",
             description: "Shared Jenkins lib branch to use.",
             name: "shared_lib_branch"

      string defaultValue: "",
             description: "Override automatic node selection and run this job on a node with this label.",
             name: "jenkins_node"
    }
    stages {
      stage("Notify GitLab"){
        steps{
          startRun()
        }
      }

      stage("Setup"){
        steps{
          script{
            try{
              env.eventsFile = "times.json"
              env.eventsFullPath = env.WORKSPACE + "/" + env.eventsFile
              env.eventsRecorder = env.WORKSPACE + "/blockchain/hermes/event_recorder.py"
              checkSkipTestsPassword()
              removeContainers()
              pruneImages()
              reportSystemStats()

              // Check parameters
              script{
                errString = "Parameter check error: "

                if (params.deploy && (!params.version_param || !params.version_param.trim())){
                  throw new Exception (errString + "A version number must be entered when the 'deploy' checkbox is checked.")
                }
              }

              // Clean the workspace
              cleanWs()

              // Add the VMware GitLab ssh key to known_hosts.
              handleKnownHosts("gitlab.eng.vmware.com")
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
        }
      }

      stage('Fetch source code') {
        parallel {
          stage("Fetch blockchain repo source") {
            steps {
              script{
                try{
                  sh 'mkdir blockchain'
                  dir('blockchain') {
                    // After the checkout, the content of the repo is directly under 'blockchain'.
                    // There is no extra 'vmwathena_blockchain' directory.
                    script {
                      env.commit = getRepoCode("git@gitlab.eng.vmware.com:blockchain/vmwathena_blockchain.git", params.blockchain_branch_or_commit)
                      env.blockchain_root = new File(env.WORKSPACE, "blockchain").toString()

                      // Check if persephone tests are to be executed in this run
                      env.run_persephone_tests = has_repo_changed('vars') || has_repo_changed('buildall.sh') || has_repo_changed('hermes') || has_repo_changed('persephone') || has_repo_changed('agent') || has_repo_changed('concord') || env.JOB_NAME.contains(master_branch_job_name) || env.JOB_NAME.contains(persephone_test_job_name)
                      sh 'echo $run_persephone_tests'
                    }
                  }
                }catch(Exception ex){
                  failRun()
                  throw ex
                }
              }
            }
          }

          stage('Fetch VMware blockchain hermes-data source') {
            steps {
              script{
                try{
                  sh 'mkdir hermes-data'
                  dir('hermes-data') {
                    script {
                      env.actual_hermes_data_fetched = getRepoCode("git@gitlab.eng.vmware.com:blockchain/hermes-data","master")
                    }
                    sh 'git checkout master'
                  }
                }catch(Exception ex){
                  failRun()
                  throw ex
                }
              }
            }
          }
        }
      }

      stage('Fetch samples from github.') {
        steps {
          script {
            try {
              dir('blockchain') {
                script {
                  sh '''
                  git clone https://github.com/vmware-samples/vmware-blockchain-samples.git
                  cd vmware-blockchain-samples
                  git checkout 9711dda
                  cd ..
                  '''
                }
              }
            } catch(Exception ex) {
              failRun()
              throw ex
            }
          }
        }
      }

      stage("Copy dependencies") {
        parallel {
          stage("Copy googletest") {
            steps() {
              script{
                try{
                  saveTimeEvent("Setup", "Copy googletest")
                  sh 'mkdir googletest'
                  dir('googletest') {
                    sh 'cp -ar /var/jenkins/workspace/googletest/* .'
                  }
                  saveTimeEvent("Setup", "Finished copying googletest")
                }catch(Exception ex){
                  failRun()
                  throw ex
                }
              }
            }
          }
          stage("Add localhost.vmware.com") {
            steps {
              dir('blockchain/vars') {
                withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                  sh 'echo "${PASSWORD}" | sudo -S ./add-localhost-vmware-com.sh'
                }
              }
            }
          }
          stage("Copy evmjit") {
            steps() {
              script{
                try{
                  saveTimeEvent("Setup", "Copy evmjit")
                  sh 'mkdir evmjit'
                  dir('evmjit') {
                    sh 'cp -ar /var/jenkins/workspace/evmjit/* .'
                  }
                  saveTimeEvent("Setup", "Finished copying evmjit")
                }catch(Exception ex){
                  failRun()
                  throw ex
                }
              }
            }
          }
          stage("Copy ethereum tests") {
            steps() {
              script{
                try{
                  saveTimeEvent("Setup", "Copy ethereum tests")
                  sh 'mkdir ethereum_tests'
                  dir('ethereum_tests') {
                    sh 'cp -ar /var/jenkins/workspace/ethereum_tests/* .'
                  }
                  saveTimeEvent("Setup", "Finished copying ethereum tests")
                }catch(Exception ex){
                  failRun()
                  throw ex
                }
              }
            }
          }
        }
      }

      stage('Write version for GUI') {
        steps() {
          script{
            try{
              dir('blockchain') {
                script {
                  version = env.version_param ? env.version_param : env.commit
                  env.version_json = createVersionInfo(version, env.commit)
                }
                // The groovy calls to create directories and files fail, only in Jenkins,
                // so do those in a shell block.  Jenkins has some quirky ideas of security?
                sh '''
                  dir=ui/src/static/data
                  mkdir -p ${dir}
                  echo ${version_json} > ${dir}/version.json
                '''
              }
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
        }
      }

      stage("Configure docker, git, and python") {
        steps {
          script{
            try{
              // Docker will fail to launch unless we fix up this DNS stuff.  It will try to use Google's
              // DNS servers by default, and here in VMware's network, we can't do that.
              // Also, since this will run on a VM which may have been deployed anywhere in the world,
              // do not hard code the DNS values.  Always probe the current environment and write
              // this file.
              // Reference: https://development.robinwinslow.uk/2016/06/23/fix-docker-networking-dns/
              withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                sh '''
                  DNS_JSON_STRING=$(echo {\\"dns\\": [\\"`nmcli dev show | grep 'IP4.DNS' | cut --delimiter=':' --fields=2 | xargs | sed 's/\\s/", "/g'`\\"]})
                  echo "${PASSWORD}" | sudo -S ls > /dev/null
                  echo $DNS_JSON_STRING | sudo tee -a /etc/docker/daemon.json
                  sudo service docker restart
                '''
              }

              withCredentials([string(credentialsId: 'ATHENA_DEPLOYER_ARTIFACTORY_PASSWORD', variable: 'ARTIFACTORY_PASSWORD')]) {
                script{
                  command = "docker login -u athena-deployer -p \"" + env.ARTIFACTORY_PASSWORD + "\" athena-docker-local.artifactory.eng.vmware.com"
                  retryCommand(command, true)
                }
              }

              withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
                sh '''
                  docker login -u blockchainrepositorywriter -p "${DOCKERHUB_PASSWORD}"
                '''
              }

              // To invoke "git tag" and commit that change, git wants to know who we are.
              // This will be set up in template VM version 5, at which point these commands can
              // be removed.
              sh '''
                git config --global user.email "vmwathenabot@vmware.com"
                git config --global user.name "build system"
              '''

              // Set up repo variables.
              script {
                env.docker_tag = env.version_param ? env.version_param : env.BUILD_NUMBER
                env.release_repo = "vmwblockchain"
                env.internal_repo_name = "athena-docker-local"
                env.internal_repo = env.internal_repo_name + ".artifactory.eng.vmware.com"

                // These are constants which mirror the DockerHub repos.  DockerHub is only used for publishing releases.
                env.release_asset_transfer_repo = env.release_repo + "/asset-transfer"
                env.release_concord_repo = env.release_repo + "/concord-core"
                env.release_ethrpc_repo = env.release_repo + "/ethrpc"
                env.release_fluentd_repo = env.release_repo + "/fluentd"
                env.release_helen_repo = env.release_repo + "/concord-ui"
                env.release_persephone_agent_repo = env.release_repo + "/agent"
                env.release_persephone_configuration_repo = env.release_repo + "/persephone-configuration"
                env.release_persephone_fleet_repo = env.release_repo + "/persephone-fleet"
                env.release_persephone_ipam_repo = env.release_repo + "/persephone-ipam"
                env.release_persephone_metadata_repo = env.release_repo + "/persephone-metadata"
                env.release_persephone_provisioning_repo = env.release_repo + "/persephone-provisioning"
                env.release_ui_repo = env.release_repo + "/ui"
                env.release_hlf_tools_repo = env.release_repo + "/fabric-tools"
                env.release_hlf_peer_repo = env.release_repo + "/fabric-peer"
                env.release_hlf_orderer_repo = env.release_repo + "/fabric-orderer"
                env.release_contract_compiler_repo = env.release_repo + "/contract-compiler"
                env.release_daml_ledger_api_repo = env.release_repo + "/daml-ledger-api"
                env.release_daml_execution_engine_repo = env.release_repo + "/daml-execution-engine"
                env.release_daml_index_db_repo = env.release_repo + "/daml-index-db"

                // These are constants which mirror the internal artifactory repos.  We put all merges
                // to master in the internal VMware artifactory.
                env.internal_asset_transfer_repo = env.release_asset_transfer_repo.replace(env.release_repo, env.internal_repo)
                env.internal_concord_repo = env.release_concord_repo.replace(env.release_repo, env.internal_repo)
                env.internal_ethrpc_repo = env.release_ethrpc_repo.replace(env.release_repo, env.internal_repo)
                env.internal_fluentd_repo = env.release_fluentd_repo.replace(env.release_repo, env.internal_repo)
                env.internal_helen_repo = env.internal_repo + "/helen"
                env.internal_persephone_agent_repo = env.release_persephone_agent_repo.replace(env.release_repo, env.internal_repo)
                env.internal_persephone_configuration_repo = env.release_persephone_configuration_repo.replace(env.release_repo, env.internal_repo)
                env.internal_persephone_fleet_repo = env.release_persephone_fleet_repo.replace(env.release_repo, env.internal_repo)
                env.internal_persephone_ipam_repo = env.release_persephone_ipam_repo.replace(env.release_repo, env.internal_repo)
                env.internal_persephone_metadata_repo = env.release_persephone_metadata_repo.replace(env.release_repo, env.internal_repo)
                env.internal_persephone_provisioning_repo = env.release_persephone_provisioning_repo.replace(env.release_repo, env.internal_repo)
                env.internal_ui_repo = env.release_ui_repo.replace(env.release_repo, env.internal_repo)
                env.internal_contract_compiler_repo = env.release_contract_compiler_repo.replace(env.release_repo, env.internal_repo)
                env.internal_hlf_tools_repo = env.release_hlf_tools_repo.replace(env.release_repo, env.internal_repo)
                env.internal_hlf_peer_repo = env.release_hlf_peer_repo.replace(env.release_repo, env.internal_repo)
                env.internal_hlf_orderer_repo = env.release_hlf_orderer_repo.replace(env.release_repo, env.internal_repo)
                env.internal_daml_ledger_api_repo = env.release_daml_ledger_api_repo.replace(env.release_repo, env.internal_repo)
                env.internal_daml_execution_engine_repo = env.release_daml_execution_engine_repo.replace(env.release_repo, env.internal_repo)
                env.internal_daml_index_db_repo = env.release_daml_index_db_repo.replace(env.release_repo, env.internal_repo)
              }

              // Docker-compose picks up values from the .env file in the directory from which
              // docker-compose is run.
              withCredentials([
                string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD'),
                string(credentialsId: 'LINT_API_KEY', variable: 'LINT_API_KEY'),
                string(credentialsId: 'FLUENTD_AUTHORIZATION_BEARER', variable: 'FLUENTD_AUTHORIZATION_BEARER'),
                string(credentialsId: 'VMC_API_TOKEN', variable: 'VMC_API_TOKEN'),
                string(credentialsId: 'DOCKERHUB_REPO_READER_PASSWORD', variable: 'DOCKERHUB_REPO_READER_PASSWORD'),
                ]) {
                sh '''
                  echo "${PASSWORD}" | sudo -S ls
                  sudo cat >blockchain/docker/.env <<EOF
asset_transfer_repo=${internal_asset_transfer_repo}
asset_transfer_tag=${docker_tag}
concord_repo=${internal_concord_repo}
concord_tag=${docker_tag}
ethrpc_repo=${internal_ethrpc_repo}
ethrpc_tag=${docker_tag}
fluentd_repo=${internal_fluentd_repo}
fluentd_tag=${docker_tag}
helen_repo=${internal_helen_repo}
helen_tag=${docker_tag}
persephone_agent_repo=${internal_persephone_agent_repo}
persephone_agent_tag=${docker_tag}
persephone_configuration_repo=${internal_persephone_configuration_repo}
persephone_configuration_tag=${docker_tag}
persephone_fleet_repo=${internal_persephone_fleet_repo}
persephone_fleet_tag=${docker_tag}
persephone_ipam_repo=${internal_persephone_ipam_repo}
persephone_ipam_tag=${docker_tag}
persephone_metadata_repo=${internal_persephone_metadata_repo}
persephone_metadata_tag=${docker_tag}
persephone_provisioning_repo=${internal_persephone_provisioning_repo}
persephone_provisioning_tag=${docker_tag}
ui_repo=${internal_ui_repo}
ui_tag=${docker_tag}
contract_compiler_repo=${internal_contract_compiler_repo}
contract_compiler_tag=${docker_tag}
hlf_tools_repo=${internal_hlf_tools_repo}
hlf_tools_tag=${docker_tag}
hlf_peer_repo=${internal_hlf_peer_repo}
hlf_peer_tag=${docker_tag}
hlf_orderer_repo=${internal_hlf_orderer_repo}
hlf_orderer_tag=${docker_tag}
daml_ledger_api_repo=${internal_daml_ledger_api_repo}
daml_ledger_api_tag=${docker_tag}
daml_execution_engine_repo=${internal_daml_execution_engine_repo}
daml_execution_engine_tag=${docker_tag}
daml_index_db_repo=${internal_daml_index_db_repo}
daml_index_db_tag=${docker_tag}
commit_hash=${commit}
LINT_API_KEY=${LINT_API_KEY}
EOF
                  cp blockchain/docker/.env blockchain/hermes/

                  # Update hermes/resources/persephone/provision-service/config.json for persephone (deployment service) testing
                  sed -i -e 's/'"<VMC_API_TOKEN>"'/'"${VMC_API_TOKEN}"'/g' blockchain/hermes/resources/persephone/provisioning/config.json
                  sed -i -e 's/'"<DOCKERHUB_REPO_READER_PASSWORD>"'/'"${DOCKERHUB_REPO_READER_PASSWORD}"'/g' blockchain/hermes/resources/persephone/provisioning/config.json
                '''
              }

              // Set up python.
              script{
                saveTimeEvent("Setup", "Set up python")
                env.python_bin = "/var/jenkins/workspace/venv_py37/bin"
                env.python = env.python_bin + "/python"

                sh '''
                   # Adding websocket-client  0.56.0 to Artifactory: https://servicedesk.eng.vmware.com/servicedesk/customer/portal/12/INTSVC-549
                   # When that is done, then start passing in -i <url to artifactory>
                   . ${python_bin}/activate
                   pip3 install -r blockchain/hermes/requirements.txt
                   deactivate
                '''
                saveTimeEvent("Setup", "Finished setting up python")
              }
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
        }
      }

      stage("Build") {
        steps {
          archiveArtifacts artifacts: env.eventsFile, allowEmptyArchive: false
          script{
            env.additional_components_to_build = additional_components_to_build
            try{
              saveTimeEvent("Build", "Start buildall.sh")
              dir('blockchain') {
                sh '''
                  ./buildall.sh --additionalBuilds ${additional_components_to_build}
                '''
              }
              saveTimeEvent("Build", "Finished buildall.sh")
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
          archiveArtifacts artifacts: env.eventsFile, allowEmptyArchive: false
        }
      }

      stage("Start tests"){
        steps {
          script {
            saveTimeEvent("Tests", "Start")
          }
        }
      }

      stage("Run tests in containers") {
        when {
          expression {
            params.run_tests
          }
        }
        steps {
          script{
            try{
              dir('blockchain/hermes'){
                withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                  script {
                    env.test_log_root = new File(env.WORKSPACE, "testLogs").toString()
                    env.sample_suite_test_logs = new File(env.test_log_root, "SampleSuite").toString()
                    env.ui_test_logs = new File(env.test_log_root, "UI").toString()
                    env.sample_dapp_test_logs = new File(env.test_log_root, "SampleDApp").toString()
                    env.core_vm_test_logs = new File(env.test_log_root, "CoreVM").toString()
                    env.helen_api_test_logs = new File(env.test_log_root, "HelenAPI").toString()
                    env.extended_rpc_test_logs = new File(env.test_log_root, "ExtendedRPC").toString()
                    env.extended_rpc_test_helen_logs = new File(env.test_log_root, "ExtendedRPC-Helen").toString()
                    env.regression_test_logs = new File(env.test_log_root, "Regression").toString()
                    env.statetransfer_test_logs = new File(env.test_log_root, "StateTransfer").toString()
                    env.mem_leak_test_logs = new File(env.test_log_root, "MemoryLeak").toString()
                    env.performance_test_logs = new File(env.test_log_root, "PerformanceTest").toString()
                    env.persephone_test_logs = new File(env.test_log_root, "PersephoneTest").toString()
                    env.lint_test_logs = new File(env.test_log_root, "LintTest").toString()
                    env.contract_compiler_test_logs = new File(env.test_log_root, "ContractCompilerTests").toString()
                    env.hlf_test_logs = new File(env.test_log_root, "HlfTests").toString()
                    env.daml_test_logs = new File(env.test_log_root, "DamlTests").toString()
                    env.time_test_logs = new File(env.test_log_root, "TimeTests").toString()

                    if (genericTests) {
                      sh '''
                        # Pull in the shell script saveTimeEvent.
                        . lib/shell/common_shell.sh
                        EVENTS_FILE="${eventsFullPath}"
                        EVENTS_RECORDER="${eventsRecorder}"

                        # So test suites not using sudo can write to test_logs.
                        rm -rf "${test_log_root}"
                        mkdir "${test_log_root}"

                        # Make sure the test framework itself can run a basic test suite.
                        saveTimeEvent SampleSuite Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py SampleSuite --resultsDir "${sample_suite_test_logs}"
                        saveTimeEvent SampleSuite End

                        saveTimeEvent SampleDAppTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py SampleDAppTests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${sample_dapp_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent SampleDAppTests End

                        saveTimeEvent CoreVMTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py CoreVMTests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${core_vm_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent CoreVMTests End

                        saveTimeEvent HelenAPITests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py HelenAPITests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${helen_api_test_logs}" --runConcordConfigurationGeneration --logLevel debug
                        saveTimeEvent HelenAPITests End

                        saveTimeEvent ExtendedRPCTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py ExtendedRPCTests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${extended_rpc_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent ExtendedRPCTests End

                        saveTimeEvent ExtendedRPCTestsEthrpc Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py ExtendedRPCTests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${extended_rpc_test_helen_logs}" --ethrpcApiUrl https://localhost/blockchains/local/api/concord/eth --runConcordConfigurationGeneration
                        saveTimeEvent ExtendedRPCTestsEthrpc End

                        saveTimeEvent RegressionTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py RegressionTests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${regression_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent RegressionTests End

                        saveTimeEvent DamlTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py DamlTests --dockerComposeFile ../docker/docker-compose-daml.yml --resultsDir "${daml_test_logs}" --runConcordConfigurationGeneration --concordConfigurationInput /concord/config/dockerConfigurationInput-daml.yaml
                        saveTimeEvent DamlTests End

                        saveTimeEvent SimpleStateTransferTest Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py SimpleStateTransferTest --dockerComposeFile ../docker/docker-compose.yml ../docker/docker-compose-static-ips.yml --resultsDir "${statetransfer_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent SimpleStateTransferTest End

                        saveTimeEvent TruffleTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py TruffleTests --logLevel debug --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${truffle_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent TruffleTests End

                        saveTimeEvent ContractCompilerTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py ContractCompilerTests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${contract_compiler_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent ContractCompilerTests End

                        # RV: Commenting out because these repeatedly cause the product to fail to launch in CI/CD.
                        # echo "${PASSWORD}" | sudo -S "${python}" main.py HlfTests --dockerComposeFile=../docker/docker-compose-hlf.yml --resultsDir "${hlf_test_logs}" --runConcordConfigurationGeneration --concordConfigurationInput /concord/config/dockerConfigurationInput-hlf.yaml

                        # Turn the time service on. When the feature flag is removed, we can remove this sed.
                        # The path to ...-time_service.yaml is different between the sed command and
                        # the hermes command, because the sed command is run outside of a container,
                        # but the configuration generation is run inside of a
                        # container. `../docker/config-public/` is mounted as `/concord/config/`
                        # during config generation.
                        saveTimeEvent TimeTests Start
                        sed -- \'s/\\(FEATURE_time_service: \\)false/\\1true/\' ../docker/config-public/dockerConfigurationInput.yaml > ../docker/config-public/dockerConfigurationInput-time_service.yaml
                        echo "${PASSWORD}" | sudo -S "${python}" main.py TimeTests --dockerComposeFile=../docker/docker-compose.yml --resultsDir "${time_test_logs}" --runConcordConfigurationGeneration --concordConfigurationInput /concord/config/dockerConfigurationInput-time_service.yaml
                        saveTimeEvent TimeTests End

                        saveTimeEvent EvilTimeTests Start
                        echo "${PASSWORD}" | sudo -S "${python}" main.py EvilTimeTests --dockerComposeFile=../docker/docker-compose.yml --resultsDir "${time_test_logs}"
                        saveTimeEvent EvilTimeTests End

                        # RV, Aug 22 2019: Commenting out because test runs are dying when running docker-compose.
                        # Jira item to resolve and uncomment: VB-1544
                        # cd suites ; echo "${PASSWORD}" | sudo -SE ./memory_leak_test.sh --testSuite CoreVMTests --repeatSuiteRun 2 --tests 'vmArithmeticTest/add0.json' --resultsDir "${mem_leak_test_logs}" ; cd ..

                        # We need to delete the database files before running UI tests because
                        # Selenium cannot launch Chrome with sudo.  (The only reason Hermes
                        # needs to be run with sudo is so it can delete any existing DB files.)
                        # Source NVM
                        . ~/.nvm/nvm.sh
                        echo "${PASSWORD}" | sudo -S rm -rf ../docker/devdata/rocksdbdata*
                        echo "${PASSWORD}" | sudo -S rm -rf ../docker/devdata/cockroachDB
                        saveTimeEvent UITests Start
                        "${python}" main.py UiTests --dockerComposeFile ../docker/docker-compose.yml ../docker/docker-compose-persephone.yml --resultsDir "${ui_test_logs}" --runConcordConfigurationGeneration
                        saveTimeEvent UITests End
                      '''
                    }
                    if (env.JOB_NAME.contains(memory_leak_job_name)) {
                      saveTimeEvent("Memory leak tests", "Start")
                      sh '''
                        echo "Running Entire Testsuite: Memory Leak..."
                        cd suites ; echo "${PASSWORD}" | sudo -SE ./memory_leak_test.sh --testSuite CoreVMTests --repeatSuiteRun 5 --resultsDir "${mem_leak_test_logs}"
                      '''
                      saveTimeEvent("Memory leak tests", "End")
                    }
                    if (env.JOB_NAME.contains(performance_test_job_name)) {
                      saveTimeEvent("Performance tests", "Start")
                      sh '''
                        echo "Running Entire Testsuite: Performance..."
                        echo "${PASSWORD}" | sudo -SE "${python}" main.py PerformanceTests --dockerComposeFile ../docker/docker-compose.yml --resultsDir "${performance_test_logs}" --runConcordConfigurationGeneration --concordConfigurationInput /concord/config/dockerConfigurationInput-perftest.yaml
                      '''
                      saveTimeEvent("Performance tests", "End")
                    }
                    if (env.JOB_NAME.contains(lint_test_job_name)) {
                      saveTimeEvent("LINT tests", "Start")
                      sh '''
                        echo "Running Entire Testsuite: Lint E2E..."

                        # We need to delete the database files before running UI tests because
                        # Selenium cannot launch Chrome with sudo.  (The only reason Hermes
                        # needs to be run with sudo is so it can delete any existing DB files.)
                        echo "${PASSWORD}" | sudo -S rm -rf ../docker/devdata/rocksdbdata*
                        echo "${PASSWORD}" | sudo -S rm -rf ../docker/devdata/cockroachDB

                        "${python}" main.py LintTests --dockerComposeFile ../docker/docker-compose.yml ../docker/docker-compose-fluentd.yml --resultsDir "${lint_test_logs}" --runConcordConfigurationGeneration
                      '''
                      saveTimeEvent("LINT tests", "End")
                    }
                  }
                }
              }
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
        }
      }

      stage("Push Images required for Deployment Services & run tests") {
        when {
          expression {
            params.run_tests
          }
        }
        steps {
          script{
            try{
              saveTimeEvent("Persephone tests", "Start")
              withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
                sh '''
                  docker login -u blockchainrepositorywriter -p "${DOCKERHUB_PASSWORD}"
                '''
              }

              dir('blockchain/hermes') {
                withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                  script {
                    sh '''
                      docker tag ${internal_persephone_agent_repo}:${docker_tag} ${release_persephone_agent_repo}:${docker_tag}
                      docker tag ${internal_concord_repo}:${docker_tag} ${release_concord_repo}:${docker_tag}
                      docker tag ${internal_ethrpc_repo}:${docker_tag} ${release_ethrpc_repo}:${docker_tag}
                      docker tag ${internal_daml_ledger_api_repo}:${docker_tag} ${release_daml_ledger_api_repo}:${docker_tag}
                      docker tag ${internal_daml_execution_engine_repo}:${docker_tag} ${release_daml_execution_engine_repo}:${docker_tag}
                      docker tag ${internal_daml_index_db_repo}:${docker_tag} ${release_daml_index_db_repo}:${docker_tag}
                    '''
                    pushDockerImage(env.release_persephone_agent_repo, env.docker_tag, false)
                    pushDockerImage(env.release_concord_repo, env.docker_tag, false)
                    pushDockerImage(env.release_ethrpc_repo, env.docker_tag, false)
                    pushDockerImage(env.release_daml_ledger_api_repo, env.docker_tag, false)
                    pushDockerImage(env.release_daml_execution_engine_repo, env.docker_tag, false)
                    pushDockerImage(env.release_daml_index_db_repo, env.docker_tag, false)

                    if (genericTests && env.run_persephone_tests) {
                      sh '''
                        echo "Running Persephone SMOKE Tests..."
                        echo "${PASSWORD}" | sudo -SE "${python}" main.py PersephoneTests --useLocalConfigService --dockerComposeFile ../docker/docker-compose-persephone.yml --resultsDir "${persephone_test_logs}" --deploymentComponents "${release_persephone_agent_repo}:${docker_tag},${release_concord_repo}:${docker_tag},${release_ethrpc_repo}:${docker_tag},${release_daml_ledger_api_repo}:${docker_tag},${release_daml_execution_engine_repo}:${docker_tag},${release_daml_index_db_repo}:${docker_tag}" --keepBlockchains on-failure
                      '''
                    }
                    if (env.JOB_NAME.contains(persephone_test_job_name)) {
                      sh '''
                        echo "Running Entire Testsuite: Persephone..."
                        echo "${PASSWORD}" | sudo -SE "${python}" main.py PersephoneTests --useLocalConfigService --dockerComposeFile ../docker/docker-compose-persephone.yml --resultsDir "${persephone_test_logs}" --tests "all_tests" --deploymentComponents "${release_persephone_agent_repo}:${docker_tag},${release_concord_repo}:${docker_tag},${release_ethrpc_repo}:${docker_tag},${release_daml_ledger_api_repo}:${docker_tag},${release_daml_execution_engine_repo}:${docker_tag},${release_daml_index_db_repo}:${docker_tag}" --keepBlockchains on-failure
                      '''
                    }
                  }
                }
              }
              saveTimeEvent("Persephone tests", "End")
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
        }
      }

      stage ("Post Memory Leak Testrun") {
        when {
          expression {
            env.JOB_NAME.contains(memory_leak_job_name) && params.run_tests
          }
        }
        stages {
          stage('Push memory leak summary into repo') {
            steps {
              script {
                try {
                  saveTimeEvent("Memory leak tasks", "Start")
                  dir('hermes-data/memory_leak_test') {
                    pushHermesDataFile('memory_leak_summary.csv')
                  }
                } catch(Exception ex){
                    failRun()
                    throw ex
                }
              }
            }
          }
          stage ('Send Memory Leak Alert Notification') {
            steps {
              script {
                try {
                  dir('hermes-data/memory_leak_test') {
                    sendAlertNotification('memory_leak')
                  }
                } catch(Exception ex){
                    failRun()
                    throw ex
                }
              }
            }
          }
          stage ('Graph') {
            steps {
              script {
                try {
                  plot csvFileName: 'plot-leaksummary.csv',
                    csvSeries: [[
                                file: 'memory_leak_summary.csv',
                                exclusionValues: '',
                                displayTableFlag: false,
                                inclusionFlag: 'OFF',
                                url: '']],
                  group: 'Memory Leak',
                  title: 'Memory Leak Summary',
                  style: 'line',
                  exclZero: false,
                  keepRecords: false,
                  logarithmic: false,
                  numBuilds: '',
                  useDescr: false,
                  yaxis: 'Leak Summary (bytes)',
                  yaxisMaximum: '',
                  yaxisMinimum: ''

                  saveTimeEvent("Memory leak tasks", "End")
                } catch(Exception ex){
                    failRun()
                    throw ex
                }
              }
            }
          }
        }
      }

      stage ("Post Performance Testrun") {
        when {
          expression {
            env.JOB_NAME.contains(performance_test_job_name) && params.run_tests
          }
        }
        stages {
          stage('Collect Performance Transaction Rate') {
            steps {
              script {
                try {
                  saveTimeEvent("Performance tasks", "Start")
                  dir('blockchain/hermes/suites') {
                    withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
                      sh '''
                        echo "Collect Transaction Rate from this run..."
                        echo "${PASSWORD}" | sudo -SE ./update_performance_result.sh --resultsDir "${performance_test_logs}"
                      '''
                    }
                  }
                } catch(Exception ex){
                    failRun()
                    throw ex
                }
              }
            }
          }
          stage('Push performance transaction rate into repo') {
            steps {
              script {
                try {
                  dir('hermes-data/performance_test') {
                    pushHermesDataFile('perf_testrun_summary.csv')
                  }
                } catch(Exception ex){
                    failRun()
                    throw ex
                }
              }
            }
          }
          stage ('Send Performance Test Alert Notification') {
            steps {
              dir('hermes-data/performance_test') {
                sendAlertNotification('performance')
              }
            }
          }
          stage ('Graph') {
            steps {
              script {
                try {
                  plot csvFileName: 'plot-summary.csv',
                    csvSeries: [[
                                file: 'perf_testrun_summary.csv',
                                exclusionValues: '',
                                displayTableFlag: false,
                                inclusionFlag: 'OFF',
                                url: '']],
                  group: 'Performance Test Transaction Rate',
                  title: 'Performance Test Transaction Rate',
                  style: 'line',
                  exclZero: false,
                  keepRecords: false,
                  logarithmic: false,
                  numBuilds: '',
                  useDescr: false,
                  yaxis: 'Performance Test Transaction Rate',
                  yaxisMaximum: '',
                  yaxisMinimum: ''

                  saveTimeEvent("Performance tasks", "End")
                } catch(Exception ex){
                    failRun()
                    throw ex
                }
              }
            }
          }
        }
      }

      stage("Finish tests"){
        steps {
          script {
            saveTimeEvent("Tests", "End")
          }
        }
      }

      stage("Save to artifactory"){
        when {
          expression {
            env.JOB_NAME.contains(master_branch_job_name) || params.deploy
          }
        }
        steps{
          script {
            try{
              saveTimeEvent("Save to artifactory", "Start")
              withCredentials([string(credentialsId: 'ARTIFACTORY_API_KEY', variable: 'ARTIFACTORY_API_KEY')]) {
                // Pass in false for whether to tag as latest because VMware's
                // artifactory does not allow re-using a tag.
                pushDockerImage(env.internal_asset_transfer_repo, env.docker_tag, false)
                pushDockerImage(env.internal_concord_repo, env.docker_tag, false)
                pushDockerImage(env.internal_ethrpc_repo, env.docker_tag, false)
                pushDockerImage(env.internal_fluentd_repo, env.docker_tag, false)
                pushDockerImage(env.internal_helen_repo, env.docker_tag, false)
                pushDockerImage(env.internal_persephone_agent_repo, env.docker_tag, false)
                pushDockerImage(env.internal_persephone_configuration_repo, env.docker_tag, false)
                pushDockerImage(env.internal_persephone_fleet_repo, env.docker_tag, false)
                pushDockerImage(env.internal_persephone_ipam_repo, env.docker_tag, false)
                pushDockerImage(env.internal_persephone_metadata_repo, env.docker_tag, false)
                pushDockerImage(env.internal_persephone_provisioning_repo, env.docker_tag, false)
                pushDockerImage(env.internal_ui_repo, env.docker_tag, false)
                pushDockerImage(env.internal_contract_compiler_repo, env.docker_tag, false)
                pushDockerImage(env.internal_daml_ledger_api_repo, env.docker_tag, false)
                pushDockerImage(env.internal_daml_execution_engine_repo, env.docker_tag, false)
                pushDockerImage(env.internal_daml_index_db_repo, env.docker_tag, false)
                pushDockerImage(env.internal_hlf_orderer_repo, env.docker_tag, false)
                pushDockerImage(env.internal_hlf_peer_repo, env.docker_tag, false)
                pushDockerImage(env.internal_hlf_tools_repo, env.docker_tag, false)
              }
              saveTimeEvent("Save to artifactory", "End")
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
        }
      }

      stage("Release") {
        when {
          environment name: 'deploy', value: 'true'
        }
        steps {
          script{
            try{
              saveTimeEvent("Push to DockerHub", "Start")
              dir('blockchain') {
                createAndPushGitTag(env.version_param)
              }

              withCredentials([string(credentialsId: 'BLOCKCHAIN_REPOSITORY_WRITER_PWD', variable: 'DOCKERHUB_PASSWORD')]) {
                sh '''
                  docker login -u blockchainrepositorywriter -p "${DOCKERHUB_PASSWORD}"
                '''
              }

              script {
                sh '''
                  docker tag ${internal_asset_transfer_repo}:${docker_tag} ${release_asset_transfer_repo}:${docker_tag}
                  docker tag ${internal_concord_repo}:${docker_tag} ${release_concord_repo}:${docker_tag}
                  docker tag ${internal_ethrpc_repo}:${docker_tag} ${release_ethrpc_repo}:${docker_tag}
                  docker tag ${internal_fluentd_repo}:${docker_tag} ${release_fluentd_repo}:${docker_tag}
                  docker tag ${internal_helen_repo}:${docker_tag} ${release_helen_repo}:${docker_tag}
                  docker tag ${internal_persephone_agent_repo}:${docker_tag} ${release_persephone_agent_repo}:${docker_tag}
                  docker tag ${internal_persephone_configuration_repo}:${docker_tag} ${release_persephone_configuration_repo}:${docker_tag}
                  docker tag ${internal_persephone_fleet_repo}:${docker_tag} ${release_persephone_fleet_repo}:${docker_tag}
                  docker tag ${internal_persephone_ipam_repo}:${docker_tag} ${release_persephone_ipam_repo}:${docker_tag}
                  docker tag ${internal_persephone_metadata_repo}:${docker_tag} ${release_persephone_metadata_repo}:${docker_tag}
                  docker tag ${internal_persephone_provisioning_repo}:${docker_tag} ${release_persephone_provisioning_repo}:${docker_tag}
                  docker tag ${internal_ui_repo}:${docker_tag} ${release_ui_repo}:${docker_tag}
                  docker tag ${internal_contract_compiler_repo}:${docker_tag} ${release_contract_compiler_repo}:${docker_tag}
                  docker tag ${internal_daml_ledger_api_repo}:${docker_tag} ${release_daml_ledger_api_repo}:${docker_tag}
                  docker tag ${internal_daml_execution_engine_repo}:${docker_tag} ${release_daml_execution_engine_repo}:${docker_tag}
                  docker tag ${internal_daml_index_db_repo}:${docker_tag} ${release_daml_index_db_repo}:${docker_tag}
                '''
                pushDockerImage(env.release_asset_transfer_repo, env.docker_tag, true)
                pushDockerImage(env.release_concord_repo, env.docker_tag, true)
                pushDockerImage(env.release_ethrpc_repo, env.docker_tag, true)
                pushDockerImage(env.release_fluentd_repo, env.docker_tag, true)
                pushDockerImage(env.release_helen_repo, env.docker_tag, true)
                pushDockerImage(env.release_persephone_agent_repo, env.docker_tag, true)
                // pushDockerImage(env.release_persephone_configuration_repo, env.docker_tag, true)
                // pushDockerImage(env.release_persephone_fleet_repo, env.docker_tag, true)
                // pushDockerImage(env.release_persephone_ipam_repo, env.docker_tag, true)
                // pushDockerImage(env.release_persephone_metadata_repo, env.docker_tag, true)
                // pushDockerImage(env.release_persephone_provisioning_repo, env.docker_tag, true)
                pushDockerImage(env.release_ui_repo, env.docker_tag, true)
                pushDockerImage(env.release_contract_compiler_repo, env.docker_tag, true)
                pushDockerImage(env.release_daml_ledger_api_repo, env.docker_tag, true)
                pushDockerImage(env.release_daml_execution_engine_repo, env.docker_tag, true)
                pushDockerImage(env.release_daml_index_db_repo, env.docker_tag, true)
              }

              saveTimeEvent("Push to DockerHub", "End")

              dir('blockchain/vars') {
                script {
                  release_notification_address_file = "release_notification_recipients.txt"

                  if (fileExists(release_notification_address_file)) {
                    release_notification_recipients = readFile(release_notification_address_file).replaceAll("\n", " ")
                    emailext body: "Changes: \n" + getChangesSinceLastTag(),
                         to: release_notification_recipients,
                         subject: "[Build] Concord version " + env.version_param + " has been pushed to DockerHub."
                  }
                }
              }
            }catch(Exception ex){
              failRun()
              throw ex
            }
          }
        }
      }

      stage("Success") {
        steps {
          script{
            passRun()
          }
        }
      }
    }// End stages

    post {
      always {
        script{
          command = "docker logout"
          retryCommand(command, false)

          command = "docker logout athena-docker-local.artifactory.eng.vmware.com"
          retryCommand(command, false)

          saveTimeEvent("Remove unnecessary docker artifacts", "Start")
          removeContainers()
          pruneImages()
          saveTimeEvent("Remove unnecessary docker artifacts", "End")
        }

        // Files created by the docker run belong to root because they were created by the docker process.
        // That will make the subsequent run unable to clean the workspace.  Just make the entire workspace dir
        // belong to builder to catch any future files.
        dir(env.WORKSPACE){
          withCredentials([string(credentialsId: 'BUILDER_ACCOUNT_PASSWORD', variable: 'PASSWORD')]) {
            sh '''
              echo "${PASSWORD}" | sudo -S chown -R builder:builder .
            '''
          }
        }

        saveTimeEvent("Gather artifacts", "Start")
        archiveArtifacts artifacts: "**/*.log", allowEmptyArchive: true
        archiveArtifacts artifacts: "**/testLogs/**/*.csv", allowEmptyArchive: true
        archiveArtifacts artifacts: "**/testLogs/**/*.txt", allowEmptyArchive: true
        archiveArtifacts artifacts: "**/*.json", allowEmptyArchive: true
        archiveArtifacts artifacts: "**/*.html", allowEmptyArchive: true
        saveTimeEvent("Gather artifacts", "End")

        // And grab the time file one more time so we can know how long gathering artifacts takes.
        archiveArtifacts artifacts: env.eventsFile, allowEmptyArchive: false

        echo 'Sending email notification...'
        emailext body: "${currentBuild.currentResult}: Job ${env.JOB_NAME} build ${env.BUILD_NUMBER}\n More info at: ${env.BUILD_URL}\nNOTE: Any failed persephone/helen deployment would be retained for the next 1 hour, before cleanup.",
        recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']],
        subject: "Jenkins Build ${currentBuild.currentResult}: Job ${env.JOB_NAME}"

        script{
          try{
            saveTimeEvent("Clean up SDDCs", "Start")
            sh 'echo Calling Job Cleanup-SDDC-folder to cleanup resources on SDDCs under folder HermesTesting...'
            build job: 'Cleanup-SDDC-folder', parameters: [[$class: 'StringParameterValue', name: 'SDDC', value: 'VMware-Blockchain-SDDC-3'], [$class: 'StringParameterValue', name: 'VMFolder', value: 'HermesTesting'], [$class: 'StringParameterValue', name: 'OLDERTHAN', value: '1']]
            build job: 'Cleanup-SDDC-folder', parameters: [[$class: 'StringParameterValue', name: 'SDDC', value: 'VMware-Blockchain-SDDC-4'], [$class: 'StringParameterValue', name: 'VMFolder', value: 'HermesTesting'], [$class: 'StringParameterValue', name: 'OLDERTHAN', value: '1']]
            saveTimeEvent("Clean up SDDCs", "End")
          }catch(Exception ex){
            failRun()
            throw ex
          }
        }

      }
    }
  }
}

// The user's parameter is top priority, and if it fails, let an exception be thrown.
// First, tries to fetch at branch_or_commit.
// Next, get master.
// Next, try to get the branch.
// Returns the short form commit hash.
String getRepoCode(repo_url, branch_or_commit){
  refPrefix = "refs/heads/"

  if (branch_or_commit && branch_or_commit.trim()){
    // We don't know if this was a branch or a commit, so don't add the refPrefix.
    checkoutRepo(repo_url, branch_or_commit)
  }else if (env.gitlabSourceBranch && env.gitlabSourceBranch.trim()){
    // When launched via gitlab triggering the pipeline plugin, there is a gitlabSourceBranch
    // environment variable.
    checkoutRepo(repo_url, refPrefix + env.gitlabSourceBranch)
  }else{
    // This was launched some other way. Just get latest.
    checkoutRepo(repo_url, "master")
  }

  return sh (
    script: 'git rev-parse --short HEAD',
    returnStdout: true
  ).trim()
}

// All that varies for each repo is the branch, so wrap this very large call.
void checkoutRepo(repo_url, branch_or_commit){
  checkout([$class: 'GitSCM', branches: [[name: branch_or_commit]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'GITLAB_LDAP_CREDENTIALS', url: repo_url]]])
}

// Given a repo and tag, pushes a docker image to whatever repo we
// are currently logged into (set up by the caller).  If tagAsLatest
// is set to true, that image will be re-tagged as latest and pushed
// again.
void pushDockerImage(repo, tag, tagAsLatest){
  if (repo.contains(env.internal_repo_name)){
    // Re-pushing to artifactory will trigger an error.
    component = repo.split("eng.vmware.com")[1]
    apiLookupString = env.internal_repo_name + component + "/" + tag

    if (existsInArtifactory(apiLookupString)){
      return
    }
  }

  retryCommand("docker push ${repo}:${tag}", true)

  if(tagAsLatest){
    command = "docker tag ${repo}:${tag} ${repo}:latest && docker push ${repo}:latest"
    retryCommand(command, true)
  }
}

// Creates a git tag and commits it. Must be called when the pwd is the
// source git directory.
void createAndPushGitTag(tag){
  sh (
    script: "git tag -a ${tag} -m 'Version tag created by the build system'",
    returnStdout: false
  )

  sh (
    script: "git push origin ${tag}",
    returnStdout: false
  )
}

// Returns all changes since the last git tag.
String getChangesSinceLastTag(){
  return sh (
    script: 'git log `git tag -l --sort=-v:refname | head -n 1`..HEAD',
    returnStdout: true
  ).trim()
}

// Use groovy to create and return json for the version and commit
// for this run.
void createVersionInfo(version, commit){
  versionObject = [:]
  versionObject.version = version
  versionObject.commit = commit
  return new JsonOutput().toJson(versionObject)
}

void pushHermesDataFile(fileToPush){
  echo "git add"
  sh (
    script: "git add ${fileToPush}",
    returnStdout: false
  )
  echo "git commit"
  sh (
    script: "git commit -m 'Update summary file'",
    returnStdout: false
  )

  echo "git push"
  sh (
    script: "git push origin master",
    returnStdout: false
  )
}

void sendAlertNotification(test_name) {
  if (test_name == 'memory_leak') {
    memory_leak_spiked_log = new File(env.mem_leak_test_logs, "memory_leak_spiked.log").toString()
    if (fileExists(memory_leak_spiked_log)) {
      echo 'ALERT: Memory Leak spiked up'

      memory_leak_alert_notification_address_file = "memory_leak_alert_recipients.txt"
      if (fileExists(memory_leak_alert_notification_address_file)) {
        memory_leak_alert_notification_recipients = readFile(memory_leak_alert_notification_address_file).replaceAll("\n", " ")
        echo 'Sending ALERT email notification...'
        emailext body: "Memory Leak Spiked up in build: ${env.BUILD_NUMBER}\n\n More info at: ${env.BUILD_URL}\nDownload Valgrind Log file (could be > 10 MB): ${env.BUILD_URL}artifact/testLogs/MemoryLeak/valgrind_concord1.log\n\nGraph: ${JOB_URL}plot",
        to: memory_leak_alert_notification_recipients,
        subject: "ALERT: Memory Leak Spiked up in build ${env.BUILD_NUMBER}"
      }
    }
  }

  if (test_name == 'performance') {
    performance_test_spiked_log = new File(env.performance_test_logs, "performance_transaction_rate_spiked.log").toString()
    if (fileExists(performance_test_spiked_log)) {
      echo 'ALERT: Performance Transaction Rate spiked up in this run'

      performance_test_alert_notification_address_file = "performance_test_alert_recipients.txt"
      if (fileExists(performance_test_alert_notification_address_file)) {
        performance_test_alert_notification_recipients = readFile(performance_test_alert_notification_address_file).replaceAll("\n", " ")
        echo 'Sending ALERT email notification...'
        emailext body: "Performance Transaction Rate Spiked up in build: ${env.BUILD_NUMBER}\n\n More info at: ${env.BUILD_URL}\nPerformance Result Log file: ${env.BUILD_URL}artifact/testLogs/PerformanceTest/performance_result.log\n\nGraph: ${JOB_URL}plot",
        to: performance_test_alert_notification_recipients,
        subject: "ALERT: Performance Transaction Rate Spiked up in build ${env.BUILD_NUMBER}"
      }
    }
  }
}

// Uses the artifactory REST API to return whether the passed in object
// exists in the VMware artifactory. The passed in object is the path
// seen in the Artifactory GUI.  e.g.
// athena-docker-local/test/concord-core/2ef3010
Boolean existsInArtifactory(String path){
  echo "Checking for existence of '" + path + "' in the VMware artifactory"
  found = false
  baseUrl = "https://build-artifactory.eng.vmware.com/artifactory/api/storage/"
  resultJsonFile = "artifactoryResult.json"
  curlCommand = "curl -s -H 'X-JFrog-Art-Api: " + env.ARTIFACTORY_API_KEY + "' " + baseUrl + path
  curlCommand += " -o " + resultJsonFile
  retryCurl(curlCommand, true)

  // If it is there, we get a structure like this:
  // {
  //   "repo" : "athena-docker-local",
  //   "path" : "/test/concord-core/2ef3010",
  //   ...
  //
  // If not, we get:
  // {
  //   "errors" : [ {
  //     "status" : 404,
  //     "message" : "Unable to find item"
  //   } ]
  // }

  resultJson = readFile(resultJsonFile)
  resultObj = new JsonSlurperClassic().parseText(resultJson)

  if (resultObj.path){
    echo "Found " + path
    return true
  }else{
    echo "Did not find " + path
    return false
  }
}

// Given a command, execute it, retrying a few
// times if there is an error. Returns true if
// the command succeeds.  If the command fails:
//   - Raises an exception if failOnError is true.
//   - Returns false if failOnError is false.
// DO NOT USE THIS DIRECTLY FOR CURL, as curl exits with 0
// for cases we would want to retry.
Boolean retryCommand(command, failOnError){
  tries = 0
  maxTries = 10
  sleepTime = 10

  while (tries < maxTries){
    tries += 1

    status = sh(
      script: command,
      returnStatus: true
    )

    if (status == 0){
      return true
    }else{
      echo "Command '" + command + "' failed."

      if (tries < maxTries){
        echo "Retrying in " + sleepTime + " seconds."
        sleep(sleepTime)
      }
    }
  }

  msg = "Failed to run the command '" + command + "'."

  if(failOnError){
    error(msg)
  }else{
    echo(msg)
    return false
  }
}

// Given a curl command (without the --dump-header parameter),
// run it, and retry if the header indicates a problem.
// Returns true if the command succeeds.  If the command fails:
//   - Raises an exception if failOnError is true.
//   - Returns false if failOnError is false.
Boolean retryCurl(command, failOnError){
  tries = 0
  maxTries = 10
  sleepTime = 10
  headerFile = "header.txt"
  command += " --dump-header " + headerFile

  sh(script: "rm -f " + headerFile)

  while (tries < maxTries){
    tries += 1

    // The retryCommand function will repeat until we get
    // a nonzero exit code, which covers things like a typo
    // in the protocol or the server not responding at all.
    commandResult = retryCommand(command, failOnError)

    if(!commandResult){
      return false
    }else{
      headers = readFile(headerFile)
      statusHeader = headers.readLines()[0]
      statusCode = statusHeader.split(" ")[1]

      if(statusCode == "500" || statusCode == "503"){
        echo "Attempt " + tries + " of command '" + command + "', returned status: '" + statusHeader + "'"

        if(tries < maxTries){
          echo "Retrying in " + sleepTime + " seconds"
          sleep(sleepTime)
        }
      }
      else{
        return true
      }
    }
  }

  msg = "Failed to run '" + command + "'."

  if(failOnError){
    error(msg)
  }else{
    echo(msg)
    return false
  }
}

// Called when it begins.
// Don't call for individual stages.
void startRun(){
  updateGitlabCommitStatus(name: "Jenkins Run", state: "running")
}

// Called when it is successful.
void passRun(){
  updateGitlabCommitStatus(name: "Jenkins Run", state: "success")
}

// Called when it fails.
void failRun(){
  updateGitlabCommitStatus(name: "Jenkins Run", state: "failed")
}

// Given a host to connect to, use ssh-keygen to see if we have
// its ssh key in known_hosts. If not, use ssh-keyscan to fetch it,
// then verify with ssh-keygen.
void handleKnownHosts(host){
  // By setting returnStatus, we will get an exit code instead of
  // having the entire Jenkins run fail.
  status = sh (
    script: "ssh-keygen -F " + host,
    returnStatus: true
  )

  if(status != 0){
    // ssh-keyscan throws a nice error; let it bubble up.
    sh (
      script: "ssh-keyscan -H " + host + " >> ~/.ssh/known_hosts",
    )

    status = sh (
      script: "ssh-keygen -F " + host,
      returnStatus: true
    )

    if(status != 0){
      error("Unable to retrieve the ssh key for " + host)
    }
  }
}

Boolean has_repo_changed(directory){
  // Check if arg: directory had a diff change with master
  absolute_path = new File(env.blockchain_root, directory).toString()
  status = sh (
    script: "git diff origin/master --name-only --exit-code '" + absolute_path + "'",
    returnStatus: true
  )

  if(status != 0){
    // There was a change in git diff
    return true
  } else{
    // There was NO change in git diff
    return false
  }
}

// Remove all containers.
// Set returnStatus to true so that a build does not fail when there are no
// containers.  That should never happen anyway, but just in case.
void removeContainers(){
  echo "Removing docker containers"
  sh(script: '''docker rm -f $(docker ps -aq) > /dev/null''', returnStatus: true)
}

// Remove unused images.
void pruneImages(){
  echo "Pruning docker images"
  sh(script: "docker system prune --force > /dev/null", returnStatus: true)
}

// Report status about this system
void reportSystemStats(){
  echo "Jenkins node networking info:"
  sh(script:
  '''
  set +x
  echo
  ifconfig | grep -A 2 "ens"
  set -x
  ''')

  echo "Jenkins node disk stats:"
  sh(script: "df -h")

  echo "Jenkins node docker system stats:"
  sh(script: "docker system df")
}

// Returns whether the current run should run tests.
Boolean runWillPush(){
  return env.JOB_NAME.contains("Master Branch") || params.deploy
}

// If someone is trying to skip tests, and it is a run which pushes
// to a repo, make sure the user has entered a password.
void checkSkipTestsPassword(){
  withCredentials([string(credentialsId: 'BYPASS_JENKINS_TESTS', variable: 'PASSWORD')]) {
    if (runWillPush() && !params.run_tests) {
      msg = "The password to bypass tests is not correct.  It is required if bypassing tests "
      msg += "for a run which pushes to a repo such as Artifactory, Bintray, or Dockerhub"

      // Encrypt PASSWORD to compare it to the user's password.
      assert Secret.fromString(PASSWORD) == params.skip_tests_password : msg
    }
  }
}

void saveTimeEvent(stage, event){
  sh(script: "python3 \"${eventsRecorder}\" record_event '" + stage + "' '" + event + "' \"${eventsFullPath}\"")
}
