
node {
  def projectDir
  environment{
      PATH="/usr/local/rvm/rubies/jruby-9.1.13.0/bin:$PATH"
  }
   stage('Preparation') { // for display purposes
      // Get some code from a GitHub repository
      sh 'whoami;  touch fake.war; rm *.war || echo "no war files"'
      
      checkout([$class: 'GitSCM', branches: [[name: '*/master-ci-demo']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'nsl-editor']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/ess-acppo/nsl-editor.git']]])
      
      checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'nsl-infra']], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/ess-acppo/nsl-infra.git']]])
    
     
   }
   stage('Unit test') {

        dir('nsl-infra'){
            // Check for postgres database. Install if not present.
            sh "ansible-playbook playbooks/postgress_install.yml -e'@vars/test_database.json'"
        }
        
        dir('nsl-editor'){
            // Check if ruby is present if not install it.
            sh 'rvm use ruby-2.3.1'
            sh 'bundle' // installs rubies




            // Create config files in ~/.nsl
            sh 'mkdir -p ~/.nsl'
            sh 'echo test:\n' +
                    '  <<: *default\n' +
                    '  database: "nsl_test"\n' +
                    '  username: "nsldev"\n' +
                    '  password: "nsldev" > ~/.nsl/editor-database.yml'
            sh 'echo Rails.configuration.mapper_root_url = \'http://localhost:9090/nsl-mapper/\'\n' +
                    'Rails.configuration.tree_editor_url = \'http://localhost:9090/nsl/tree-editor/\'\n' +
                    'Rails.configuration.mapper_shard = \'apni\'\n' +
                    'Rails.configuration.api_key = \'test-api-key\'\n' +
                    'Rails.configuration.environment = \'test\'\n' +
                    '\n' +
                    'Rails.configuration.services_clientside_root_url = \'http://localhost:9090/\'\n' +
                    'Rails.configuration.nsl_links = \'http://biodiversity.org.au/nsl/services/\'\n' +
                    'Rails.configuration.services = \'http://localhost:9090/nsl/services/\'\n' +
                    'Rails.configuration.name_services = \'http://localhost:9090/nsl/services/name/apni/\'\n' +
                    'Rails.configuration.reference_services = \'http://localhost:9090/nsl/services/reference/apni/\'\n' +
                    '\n' +
                    'Rails.configuration.session_key_tag = \'local_test\'\n' +
                    '\n' +
                    'Rails.configuration.ldap_admin_username = "uid=admin,ou=system"\n' +
                    'Rails.configuration.ldap_admin_password = "secret"\n' +
                    'Rails.configuration.ldap_base = "dc=com"\n' +
                    'Rails.configuration.ldap_host = \'localhost\'\n' +
                    'Rails.configuration.ldap_port = 10389\n' +
                    'Rails.configuration.ldap_users = "ou=users,dc=example,dc=com"\n' +
                    'Rails.configuration.ldap_groups = "ou=groups,dc=example,dc=com" > ~/.nsl/test/editor-config.rb'
            // Run ruby tests
            sh 'bundle exec rake test:controllers' // tests controllers. "bundle exec" ensures required rubies are present
            sh 'bundle exec rake test:models'      // tests models. "bundle exec" ensures required rubies are present
           }
      
   }
   stage('Building war') {
      withEnv(['PATH+=/opt/jruby-9.1.13.0/bin']){
        dir('nsl-editor'){
        sh " echo $PATH; which jruby; JAVA_OPTS='-server -d64'; jruby -S bundle install --without development test;bundle exec rake assets:clobber;bundle exec rake assets:precompile  RAILS_ENV=production RAILS_GROUPS=assets;bundle exec warble"
        script{
            projectDir = pwd() 
            def version = new File(projectDir+"/config/version.txt")
            def war = new File(projectDir+"/nsl-editor.war")
            war.renameTo(projectDir+"/nxl#editor##${version.text.trim()}.war")
        }
        }
      }
      
      
   }
   stage("Deploy") {
      dir('nsl-infra'){
          if (ENVIRONMENT_NAME) {
              def extra_vars = /'{"nxl_env_name":"\u0024ENVIRONMENT_NAME","apps":[{"app": "editor"}], "war_names": [{"war_name": "nxl#editor##1.53"}   ],   "war_source_dir": "$projectDir"}'/
              sh "sed -ie 's/.*instance_filters = tag:env=.*\$/instance_filters = tag:env=$ENVIRONMENT_NAME/g' aws_utils/ec2.ini && ansible-playbook  -i aws_utils/ec2.py -u ubuntu playbooks/deploy.yml -e $extra_vars '@shard_vars/icn.json'"
          }else if (INVENTORY_NAME){
              def extra_vars = /'{"nxl_env_name":"\u0024ENVIRONMENT_NAME","apps":[{"app": "editor"}], "war_names": [{"war_name": "nxl#editor##1.53"}   ],   "war_source_dir": "$projectDir"}'/
              sh "ansible-playbook -vvv -i inventory/$INVENTORY_NAME -u ubuntu playbooks/deploy.yml -e $extra_vars -e '@shard_vars/icn.json'"
          }
      }
   }
}


