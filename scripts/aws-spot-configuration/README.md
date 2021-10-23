## AWS spot instance fleet
Amazon EC2 Spot Instances let you take advantage of unused EC2 capacity in the AWS cloud,  
Currently spot instances are being used in Syclla CI for:
* Next process for master gating and releases
* Parallel dtests (debug and release modes)
* RE promotion process

## Configuration files
- The json files in this folder (aws-spot-configuration) are used as AMI templates.  
- Each json file has a long hash on `UserData` key - it's `userData2.0.txt` file content that will run the `cloud-init.sh` script on boot of every new instance,  
- The `cloud-init.sh` script is located on S3 and require a manual update on each content change.  
- All templates are based on Fedora 33 Cloud Base Images (x86_64) HVM - https://aws.amazon.com/marketplace/pp/B08LZY538M?ref=cns_srchrow

## Create new spot-fleet request
1. Make sure you have AWS CLI installed on your workstation (https://aws.amazon.com/cli)
2. Make sure this repo (scylla-pkg) is cloned on your workstation (https://github.com/scylladb/scylla-pkg)
3. `cd scylla-pkg/scripts/aws-spot-configuration/`
4. Run the following command for requesting a new spot-fleet:
    ```
    aws ec2 request-spot-fleet --region eu-west-1 --spot-fleet-request-config file://<your-new-json-file>
    ```
    The output should be:
    ```
    {
        "SpotFleetRequestId": "<some-long-id>"
    }
    ```
5. Open your browser and access https://jenkins.scylladb.com/configureClouds/
6. Scroll down and click on: `Add a new cloud --> Amazon EC2 Fleet`, then fill the following information:

   * `Name` FleetCloud-<name\>
   * `AWS Credentials` choose jenkins2 aws account
   * `Region` Choose the same region you created your fleet
   * `EC2 Fleet` Choose your ths spot ID as shown in previous command (SpotFleetRequestId)
   * `Launcher` Launch agents via SSH
     * `Credentials` user-jenkins_scylla-qa-ec2.pem
     * `Host Key Verification Strategy` Non verifying Verification Strategy
   * `Label` Set a unique label
   * `Jenkins Filesystem Root` /jenkins/
   * `Number of Executors` 1
   * `Max Idle Minutes Before Scaledown` 5
   * `Minimum Cluster Size` 0
   * `Maximum Cluster Size` 10
   * Check `Disable build resubmit`
   * `Maximum Init Connection Timeout in sec` 600
   * `Cloud Status Interval in sec` 10
7. Save 
