# Wanaku Suite 

The job is to develop a JUnit based test suite for the Wanaku project.

The files will available in the `artifacts` directory. The layout should look like this:

wanaku-test/ 
   |- /artifacts
        |- wanaku-router-0.1.0-SNAPSHOT.tar.gz 
        |- camel-integration-capability-0.1.0-SNAPSHOT.tar.gz 
 

The suite has to be able to perform the following steps:

0. Run keycloak
1. Configure credentials (using the script)
2. Run Wanaku 
2. Setup the tools/resources for Wanaku 
3. Run