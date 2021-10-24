def setParamValueForRunBuilds (Map args) {
	// Define the parameter name and the value upon dryRun and debugBuild
	// Any type (mandatory): parameter - Name of parameter to set value to.
	// Any type (Default ""): value - Value for the parameter
	// Any type (Default ""): dryRunValue - Value for the parameter if this is a dry run
	// Any type (Default ""): debugValue - Value for the parameter if this is a debug build
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).

	// This DUMMY_PARAM is a trick to enable sending a value to the given parameter on dryRun / debug build, but
	// not sending any value not on other cases (so you send a value to a non-existing parameter).
	// Sending an empty value will over-ride the job's default, which is not wanted in most cases.

	general.traceFunctionParams ("jenkins.setParamValueForRunBuilds", args)
	general.errorMissingMandatoryParam ("jenkins.setParamValueForRunBuilds", [ parameter: "$args.parameter" ])

	boolean dryRun = args.dryRun ?: false
	def value = args.value ?: ""
	def dryRunValue = args.dryRunValue ?: ""
	def debugValue = args.debugValue ?: ""
	String paramNameToReturn = args.parameter
	def paramValueToReturn = value

	if (dryRun) {
		if (dryRunValue) {
			paramValueToReturn = dryRunValue
		} else {
			paramNameToReturn  = "DUMMY_PARAM"
			paramValueToReturn = ""
		}
	} else if (debugBuild()) {
		if (debugValue) {
			paramValueToReturn = debugValue
		} else {
			paramNameToReturn  = "DUMMY_PARAM"
			paramValueToReturn = ""
		}
	} else if (! value) {
		paramNameToReturn = "DUMMY_PARAM"
		paramValueToReturn = ""
	}

	return [paramNameToReturn, paramValueToReturn]
}

def runBuilds (Map args) {
	// Run jenkins jobs from a given list in parallel
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): buildShouldCallDependentJobs - for builds that may or may not call other builds.
	// boolean (default false): buildShouldCallPackageJobs - for "build" that can call only package jobs (not relevant if buildShouldCallDependentJobs is true)
	// boolean (default false): skipTests - for builds that may or may not skip running tests.
	// boolean (default false): skipAdditionalTests - for builds that may or may not skip running additional tests.
	// boolean (default false): failIfCallFailed - Whether to fail if one of the called builds fails.
	// boolean (default false): waitForLongBuilds - whether to wait for called builds.
	// String (default stable branch): machineImageBranch - branch to run machine image from
	// String (default: branchProperties.qaSpotProvisionType): qaJobProvisionType -Type of instance provision. available options: spot_low_price and on_demand
	// String (default: branchProperties.qaNodesDailyPostBehavior): qaNodesPostBehaviorType - What to do with node at the end (destroy / keep-on-failure)
	// String (default: stable branch or git properties value if exists): scyllaBranch - Sha of scylla git repo
	// String (mandatory): buildsToRun - comma separated list of builds to call.
	// String (default: branchProperties.calledBuildsDir): calledBuildsDir in which dir (folder) on jenkins builds will run (releng-testing or production)
	// String (default: empty, let called build use its default): artifactSourceJob - base build name (next / build) - from where should the called builds take artifacts
	// String (default: empty, let called build use its default): artifactSourceJobNum - number of base job (from where should the called builds take artifacts)
	// String (default: empty, let called build use its default): artifactWebUrl - URL of artifacts that called jobs should use (rpm, deb, reloc)
	// String (default: empty, let called build use its default): relengBranch - from which releng branch the called builds should run.
	// String (default: empty, let called build use its default): relengRepo - from which releng repo the called builds should run.
	// String (default: empty): scyllaManagerPackage - Relevant for manager-dtest only
	// String (mandatory): managerAgentRepoListUrl - Ubuntu or Centos manager's repo/list url
	// String (default empty, on debug - smoke): includeDtests - list of dtests to run
	// String (default empty): gce_image_db - full path to GCE image id location
	// String (default destroy): post_behavior_db_nodes - choose if to destroy the instance or keep it once job is done. On GCE we keep on AMI we destroy
	// String (default empty, let called build use its default): x86TargetNode - Name of node to run the jobs
	// String (default empty, let called build use its default): armTargetNode - Name of node to run the jobs
	// String (default: ubuntu:20.04): osDistro - On which distribution to run a job (ubuntu:20.04|centos:7)
	// String (default: n1-standard-2): instanceTypeGCE - instance type for gce test

	general.traceFunctionParams ("jenkins.runBuilds", args)
	general.errorMissingMandatoryParam ("jenkins.runBuilds", [buildsToRun: "$args.buildsToRun", managerAgentRepoListUrl: "$args.managerAgentRepoListUrl"])

	boolean dryRun = args.dryRun ?: false
	boolean useStableShas = args.useStableShas ?: false
	boolean buildShouldCallDependentJobs = args.buildShouldCallDependentJobs ?: false
	boolean buildShouldCallPackageJobs = args.buildShouldCallPackageJobs ?: false
	boolean skipTests = args.skipTests ?: false
	boolean skipAdditionalTests = args.skipAdditionalTests ?: false
	boolean failIfCallFailed = args.failIfCallFailed ?: false
	boolean waitForLongBuilds = args.waitForLongBuilds ?: false
	String qaJobProvisionType = args.qaJobProvisionType ?: branchProperties.qaSpotProvisionType
	String qaNodesDailyPostBehavior = args.qaJobProvisionType ?: branchProperties.qaNodesDailyPostBehavior
	String calledBuildsDir = args.calledBuildsDir ?: branchProperties.calledBuildsDir
	String x86TargetNodeParamValue = args.x86TargetNode ?: ""
	String armTargetNodeParamValue = args.armTargetNode ?: ""
	String includeDtestsParamValue = args.includeDtests ?: ""
	String gceImageDbUrl = args.gceImageDbUrl ?: ""
	String scyllaManagerPackage = args.scyllaManagerPackage ?: ""
	String postBehaviorDbNodes = args.postBehaviorDbNodes ?: "destroy"

	String scyllaBranch = args.scyllaBranch ?: branchProperties.stableBranchName
	String scyllaMachineImageBranch = args.machineImageBranch ?: branchProperties.stableBranchName
	String scyllaCcmBranch = branchProperties.stableBranchName
	String scyllaDtestBranch = branchProperties.stableBranchName
	String artifactSourceJob = args.artifactSourceJob ?: ""
	String artifactSourceJobNum = args.artifactSourceJobNum ?: ""
	String artifactWebUrl = args.artifactWebUrl ?: ""
	String relengBranch = args.relengBranch ?: ""
	String relengRepo = args.relengRepo ?: ""
	String osDistro = args.osDistro ?: "ubuntu:20.04"

	(x86TargetNodeParamName, x86TargetNodeParamValue) = setParamValueForRunBuilds (
		parameter: "X86_NODE_PARAM",
		value: x86TargetNodeParamValue,
		dryRun: dryRun,
		dryRunValue: generalProperties.packagerJenkinsLabel,
		debugValue: generalProperties.packagerJenkinsLabel,
	)

	(armTargetNodeParamName, armTargetNodeParamName) = setParamValueForRunBuilds (
		parameter: "ARM_NODE_PARAM",
		value: armTargetNodeParamValue,
		dryRun: dryRun,
		dryRunValue: generalProperties.packagerJenkinsLabel,
		debugValue: generalProperties.packagerJenkinsLabel,
	)

	(forceParamName, forceParamValue) = setParamValueForRunBuilds (
		parameter: "FORCE_RUNNING_BUILDS_EVEN_IF_NO_CHANGES",
		value: "",
		dryRun: dryRun,
		dryRunValue: true,
		debugValue: true,
	)

	(relengBranchParamName, relengBranchParamValue) = setParamValueForRunBuilds (
		parameter: "RELENG_BRANCH",
		value: relengBranch,
		dryRun: dryRun,
		dryRunValue: relengBranch,
		debugValue: relengBranch,
	)

	(relengRepoParamName, relengRepoParamValue) = setParamValueForRunBuilds (
		parameter: "RELENG_REPO",
		value: relengRepo,
		dryRun: dryRun,
		dryRunValue: relengRepo,
		debugValue: relengRepo,
	)

	(promoteLatestParamName, promoteLatestParamValue) = setParamValueForRunBuilds (
		parameter: "SKIP_PROMOTE_LATEST",
		value: "",
		dryRun: dryRun,
		dryRunValue: false,
		debugValue: true,
	)

	(includeDtestsParamName, includeDtestsParamValue) = setParamValueForRunBuilds (
		parameter: "INCLUDE_DTESTS",
		value: "",
		dryRun: dryRun,
		debugValue: generalProperties.dtestIncludeSmoke,
	)

	(relocJobParamName, artifactSourceJob) = setParamValueForRunBuilds (
		parameter: "ARTIFACT_SOURCE_JOB_NAME",
		value: artifactSourceJob,
		dryRun: dryRun,
		dryRunValue: "",
		debugValue: artifactSourceJob,
	)

	(artifactSourceJobNumParamName, artifactSourceJobNum) = setParamValueForRunBuilds (
		parameter: "ARTIFACT_SOURCE_BUILD_NUM",
		value: artifactSourceJobNum,
		dryRun: dryRun,
		dryRunValue: "",
		debugValue: artifactSourceJobNum,
	)

	(artifactWebUrlParamName, artifactWebUrl) = setParamValueForRunBuilds (
		parameter: "ARTIFACT_WEB_URL",
		value: artifactWebUrl,
		dryRun: dryRun,
		dryRunValue: "",
		debugValue: artifactWebUrl,
	)

	(skipTestsParamName, skipTests) = setParamValueForRunBuilds (
		parameter: "SKIP_TEST",
		value: skipTests,
		dryRun: dryRun,
		dryRunValue: false,
		debugValue: skipTests,
	)

	(skipAdditionalTestsParamName, skipAdditionalTests) = setParamValueForRunBuilds (
		parameter: "SKIP_ADDITIONAL_TESTS",
		value: skipAdditionalTests,
		dryRun: dryRun,
		dryRunValue: false,
		debugValue: skipAdditionalTests,
	)

	if (debugBuild ()) {
		qaJobProvisionType = branchProperties.qaSpotProvisionType
		qaNodesDailyPostBehavior = branchProperties.qaNodesDailyPostBehavior
	}

	try {
		scyllaBranch = gitRepoShas.scylla_branch
		scyllaMachineImageBranch = gitRepoShas.scylla_machine_image_branch
		scyllaCcmBranch = gitRepoShas.scylla_ccm_branch
		scyllaDtestBranch = gitRepoShas.scylla_dtest_branch
	} catch (error) {
		echo "gitRepoShas is not defined. Using default branches."
	}

	ArrayList jobNames = args.buildsToRun.split('\\,')
	def parallelJobs = [:]
	jobNames.each { jobName ->
		echo "Calling to parallel for job $jobName"
		parallelJobs[jobName] = { ->
			if (jenkins.jobEnabled("$calledBuildsDir$jobName")) {

				if (dryRun) {
					if (jobName.contains("byo/byo-")) {
						artifactSourceJob = "next"
					}
					if (jobName == branchProperties.buildJobName) {
						buildShouldCallDependentJobs = false
						buildShouldCallPackageJobs = false
					}
				}
				jobResults=build job: "$calledBuildsDir$jobName",
					parameters: [
						[$class: 'StringParameterValue', name: 'SCYLLA_BRANCH', value: scyllaBranch], // Needed for build, dtest
						[$class: 'StringParameterValue', name: 'MACHINE_IMAGE_BRANCH', value: scyllaMachineImageBranch], // Needed for build (with reloc), dtest
						[$class: 'StringParameterValue', name: 'SCYLLA_CCM_BRANCH', value: scyllaCcmBranch], // Needed for dtest build
						[$class: 'StringParameterValue', name: 'SCYLLA_DTEST_BRANCH', value: scyllaDtestBranch],	// Needed for dtest build
						[$class: 'StringParameterValue', name: relengRepoParamName, value: relengRepoParamValue],	// Needed for debug jobs
						[$class: 'StringParameterValue', name: relengBranchParamName, value: relengBranchParamValue],	// Needed for debug jobs
						[$class: 'StringParameterValue', name: relocJobParamName, value: artifactSourceJob],	// Needed for dtest build
						[$class: 'StringParameterValue', name: artifactSourceJobNumParamName, value: artifactSourceJobNum],	// Needed for reloc based builds. Send integer as a string
						[$class: 'StringParameterValue', name: artifactWebUrlParamName, value: artifactWebUrl],	// Needed for reloc based builds when run on cloud machine
						[$class: 'StringParameterValue', name: x86TargetNodeParamName, value: x86TargetNodeParamValue],
						[$class: 'StringParameterValue', name: armTargetNodeParamName, value: armTargetNodeParamValue],
						[$class: 'StringParameterValue', name: 'provision_type', value: qaJobProvisionType],
						[$class: 'StringParameterValue', name: 'post_behavior_db_nodes', value: qaNodesDailyPostBehavior],
						[$class: 'StringParameterValue', name: 'post_behavior_loader_nodes', value: qaNodesDailyPostBehavior],
						[$class: 'StringParameterValue', name: 'post_behavior_monitor_nodes', value: qaNodesDailyPostBehavior],
						[$class: 'StringParameterValue', name: includeDtestsParamName, value:includeDtestsParamValue],
						[$class: 'StringParameterValue', name: 'gce_image_db', value: gceImageDbUrl],
						[$class: 'StringParameterValue', name: 'scylla_mgmt_agent_repo', value: args.managerAgentRepoListUrl],
						[$class: 'StringParameterValue', name: 'scylla_mgmt_repo', value: args.managerAgentRepoListUrl],
						[$class: 'StringParameterValue', name: 'scylla_manager_package', value: scyllaManagerPackage],
						[$class: 'StringParameterValue', name: 'post_behavior_db_nodes', value: postBehaviorDbNodes],
						[$class: 'StringParameterValue', name: 'OS_DISTRO', value: osDistro], // Needed for AMI
						[$class: 'StringParameterValue', name: 'instance_type', value: args.instaceTypeGCE], // needed for gce image tests
						[$class: 'BooleanParameterValue', name: 'RUN_DOWNSTREAM_JOBS', value: buildShouldCallDependentJobs], // Needed for build
						[$class: 'BooleanParameterValue', name: 'RUN_PACKAGE_JOBS', value: buildShouldCallPackageJobs], // Needed for build
						[$class: 'BooleanParameterValue', name: 'RUN_RELEASE_JOBS', value: buildShouldCallDependentJobs], // Needed for package-release
						[$class: 'BooleanParameterValue', name: skipTestsParamName, value: skipTests], // Needed for CentOS, unified, AMI, GCE
						[$class: 'BooleanParameterValue', name: skipAdditionalTestsParamName, value: skipAdditionalTests], // Needed for CentOS, unified, AMI, GCE
						[$class: 'BooleanParameterValue', name: forceParamName, value: forceParamValue], // Needed for daily
						[$class: 'BooleanParameterValue', name: 'DRY_RUN', value: dryRun], // Run jobs as dry run
						[$class: 'BooleanParameterValue', name: promoteLatestParamName, value: promoteLatestParamValue] // For CentOS and Unified deb
					],
					propagate: failIfCallFailed, // I don't want to fail caller if a sub-job failed to run. But I want to fail if could not run at all. So I can't use true here + try & catch on caller.
					wait: waitForLongBuilds
			}
		}
	};
	parallel parallelJobs
}

def jenkinsNode (String test) {
	def nodeName = sh(script: "cat /var/lib/jenkins/nodes/${test}/config.xml | awk -F'<host>' '{print \$2}' | awk -F'</host>' '{print \$1}' | grep ^[0-9]", returnStdout: true).trim()
	return nodeName
}

def checkJenkinsNodeDiskStatus (String DISK_USAGE_THRESHOLD) {
	def myList = []
	for (node in jenkins.model.Jenkins.instance.slaves) {
		def computer = node.toComputer()
		if (computer.getChannel() == null) continue
		def percentage = computer.getMonitorData()['org.jenkins.ci.plugins.percentagecolumn.PercentageDiskSpaceMonitor']
		def spaceLeftInGB = computer.getMonitorData()['hudson.node_monitors.DiskSpaceMonitor']
		String str = "$percentage"
		def diskUsageString = str.replaceAll(".0 %"," ")
		def diskUsageInt = "$diskUsageString" as Integer
		def DISK_USAGE_THRESHOLD_INT = DISK_USAGE_THRESHOLD as Integer
		//Only nodes that are online will have value inside percentage param. all offline nodes will have null value which we ignore
		if (percentage) {
			if (diskUsageInt >= DISK_USAGE_THRESHOLD_INT) {
				myList.add("${node.getDisplayName()}:\t${percentage}\t${spaceLeftInGB}\n\n")
				echo "Under threshold node:\t${node.getDisplayName()}:\t${percentage}\t${spaceLeftInGB}\n\n"
			} else {
				echo "Node ${node.getDisplayName()} disk space is OK"
			}
		}
	}
	return "$myList"
}

def compressJenkinsNodeLogs (String DISK_USAGE_THRESHOLD){
	def nodeList = []
	for (node in jenkins.model.Jenkins.instance.slaves) {
		def compressedFilterValue = "qa"
		def computer = node.toComputer()
		if (computer.getChannel() == null) continue
		def percentage = computer.getMonitorData()['org.jenkins.ci.plugins.percentagecolumn.PercentageDiskSpaceMonitor']
		String str = "$percentage"
		def diskUsageString = str.replaceAll(".0 %"," ")
		def diskUsageInt = "$diskUsageString" as Integer
		def DISK_USAGE_THRESHOLD_INT = DISK_USAGE_THRESHOLD as Integer
		if (percentage) {
			if (diskUsageInt >= DISK_USAGE_THRESHOLD_INT) {
				if (node.getDisplayName().contains("${compressedFilterValue}")) {
					nodeList.add("${node.getDisplayName()}")
				}
			}
		}
	}
	return "$nodeList"
}

boolean jobEnabled (String jobName) {
	echo "Checking if Job $jobName exists / enabled"
	boolean jobExists = true
	try {
		if (Jenkins.instance.getItemByFullName(jobName).isBuildable()) {
			echo "Job $jobName is enabled"
			return true
		} else {
			echo "Job $jobName is disabled, Skipping"
			return false
		}
	} catch (error) {
		echo "Error: General error |$error| while checking if job |$jobName| enabled (job does not exist)"
		jobExists = false
	}
	if (! jobExists) {
		error ("|$jobName| does not exist, or other error occured while checking it. Please check log above.")
	}
}

boolean runATestJob (Map args) {
	// This is a simple helper function for runTestJob
	// All parameter documentaiton is there.

	boolean dryRun = args.dryRun ?: false
	boolean waitForLongBuilds = args.waitForLongBuilds ?: false
	String qaJobProvisionType = args.qaJobProvisionType ?: branchProperties.qaSpotProvisionType
	String qaNodesDailyPostBehavior = args.qaJobProvisionType ?: branchProperties.qaNodesDailyPostBehavior
	String calledBuildsDir = args.calledBuildsDir ?: branchProperties.calledBuildsDir

	boolean testFailed=false

	if (jenkins.jobEnabled("$calledBuildsDir${args.testJobName}")) {
		if (dryRun) {
			echo "dryRun: call $calledBuildsDir${args.testJobName}"
		} else {
			jobResult=build job: "$calledBuildsDir${args.testJobName}",
			parameters: [
				[$class: 'StringParameterValue', name: args.urlParamName, value: args.urlParamValue],
				[$class: 'StringParameterValue', name: 'provision_type', value: qaJobProvisionType],
				[$class: 'StringParameterValue', name: 'post_behavior_db_nodes', value: qaNodesPostBehaviorType],
				[$class: 'StringParameterValue', name: 'unified_package', value: ""],
				[$class: 'BooleanParameterValue', name: 'nonroot_offline_install', value: false],
			],
			propagate: false,
			wait: waitForLongBuilds

			String jobStatus = jobResult.getResult()
			echo "Job status: |$jobStatus|"
			if (jobStatus != "SUCCESS") {
				testFailed=true
			}
		}
	}
	return testFailed
}

def runTestJob (Map args) {
	// Run all enabled builds in given list, with parameters
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): failIfCallFailed - Whether to fail if one of the called builds fails.
	// boolean (default false): waitForLongBuilds - whether to wait for called builds.
	// boolean (default false): runJobsSerial - Whether to run the jobs one by one, not in parallel
	// String (mandatory): testsToRun - comma separated list of builds to call.
	// String (default: branchProperties.qaSpotProvisionType): qaJobProvisionType -Type of instance provision. available options: spot_low_price and on_demand
	// String (default: branchProperties.qaNodesDailyPostBehavior): qaNodesPostBehaviorType - What to do with node at the end (destroy / keep-on-failure)
	// String (default: branchProperties.calledBuildsDir): calledBuildsDir in which dir (folder) on jenkins builds will run (releng-testing or production)
	// String (mandatory) urlParamName: The name of parameter for testing URL such as "scylla_repo",
	// String (mandatory) urlParamValue: The value of parameter for testing URL


	general.traceFunctionParams ("jenkins.runTestJob", args)
	general.errorMissingMandatoryParam ("jenkins.runTestJob", [testsToRun: "$args.testsToRun", urlParamName: "$args.urlParamName", urlParamValue: "$args.urlParamValue"])

	boolean dryRun = args.dryRun ?: false
	boolean failIfCallFailed = args.failIfCallFailed ?: false
	boolean waitForLongBuilds = args.waitForLongBuilds ?: false
	// TODO this ability to run the tests serially is a workaround till
	// https://github.com/scylladb/scylla-cluster-tests/issues/3356 is solved.
	boolean runJobsSerial = args.runJobsSerial ?: false
	String qaJobProvisionType = args.qaJobProvisionType ?: branchProperties.qaSpotProvisionType
	String qaNodesDailyPostBehavior = args.qaJobProvisionType ?: branchProperties.qaNodesDailyPostBehavior
	String calledBuildsDir = args.calledBuildsDir ?: branchProperties.calledBuildsDir

	boolean testFailed = false
	String failedTests = ""

	if (dryRun || !waitForLongBuilds) {
		runJobsSerial = true
	}
	ArrayList testJobNames = args.testsToRun.split('\\,')
	if (runJobsSerial) {
		testJobNames.each { testJobName ->
			boolean currentTestFailed = runATestJob (
				dryRun: dryRun,
				waitForLongBuilds: waitForLongBuilds,
				calledBuildsDir: calledBuildsDir,
				testJobName: testJobName,
				qaJobProvisionType: qaJobProvisionType,
				qaNodesPostBehaviorType: qaNodesPostBehaviorType,
				urlParamName: args.urlParamName,
				urlParamValue: args.urlParamValue,
			)
			if (currentTestFailed) {
				testFailed = true
				failedTests += "${testJobName}, "
			}
		}
	} else {
		def parallelTestJobs = [:]
		testJobNames.each { testJobName ->
			parallelTestJobs[testJobName] = { ->
				boolean currentTestFailed = runATestJob (
					dryRun: dryRun,
					waitForLongBuilds: waitForLongBuilds,
					calledBuildsDir: calledBuildsDir,
					testJobName: testJobName,
					qaJobProvisionType: qaJobProvisionType,
					qaNodesPostBehaviorType: qaNodesPostBehaviorType,
					urlParamName: args.urlParamName,
					urlParamValue: args.urlParamValue,
				)
				if (currentTestFailed) {
					testFailed = true
					failedTests += "${testJobName}, "
				}
			}
		}
		parallel parallelTestJobs
	}
	if (failIfCallFailed && testFailed) {
		error("Some test job(s) were not success (on $calledBuildsDir): $failedTests Check log for details")
	}
}

def checkAndTagAwsInstance (String runningUserID) {
	// TAG the spot instance with more tags
		wrap([$class: 'BuildUser']) {
			withCredentials([string(credentialsId: 'jenkins2-aws-secret-key-id', variable: 'AWS_ACCESS_KEY_ID'),
			string(credentialsId: 'jenkins2-aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')]) {
				env.internalAwsIP = generalProperties.internalAwsIP
				if (runningOnAwsInstance ()) {
					sh "cat /cloud-init.log" // Print cloud-init.log file
					def runningInstanceId = sh(script: "curl http://$internalAwsIP/latest/meta-data/instance-id | grep '^i-[0-9]*' ", returnStdout: true).trim()
					def regionId = sh(script: "curl http://$internalAwsIP/latest/meta-data/placement/availability-zone | sed 's/.\$//'", returnStdout: true).trim()
					def isSpotInstance = sh(script: "aws ec2 describe-spot-instance-requests \
						--region $regionId \
						--filter Name=instance-id,Values=$runningInstanceId | grep SpotInstanceRequestId | awk -F'\"' \'{print \$4}\'", returnStdout: true).trim()
					if (!isSpotInstance.isEmpty()) {
						sh "aws ec2 --region " + regionId + " create-tags \
							--resources " + runningInstanceId + " --tag Key=RunByUser,Value=" + runningUserID + " Key=JenkinsJobTag,Value=" + BUILD_TAG + " Key=NodeType,Value=compile-spotfleet Key=keep,Value=5 Key=keep_action,Value=terminate"
					}
					def instanceType = sh(script: "curl http://${internalAwsIP}/latest/meta-data/instance-type", returnStdout: true).trim()
					echo "instanceType: ${instanceType}"

					checkLocalDiskAvailable()
			}
		}
	}
}

def checkLocalDiskAvailable () {
	int numOfLocalDisks = sh(script: "lsblk | grep disk | wc -l", returnStdout: true).trim().toInteger()
	boolean localDiskMountExists = sh(script: "findmnt /dev/mapper/vg_jenkins-vol_data", returnStatus: true) == 0
	if (numOfLocalDisks > 1) {
		if (localDiskMountExists) {
			if (isDiskCleanupNeeded()) {
				git.cleanWorkSpaceUponRequest()
			}
		} else {
			echo "Local disk configuration couldn't be found, terminating the instance"
			currentBuild.description = "Local disk is not available - terminating spot instance"
			sh "sudo shutdown -h now"
		}
	}
}

boolean isDiskCleanupNeeded() {
	String totalInUseStr = sh(script:"df -kh /boot |awk 'NR==2 {print \$5}' | sed -e 's/%//'", returnStdout: true).trim()
	println "totalInUseStr Value is:" + totalInUseStr
	int totalInUse = general.parseIntSafe(totalInUseStr)
	println "Total disk usage: " + totalInUse
	int diskInUseCleanupThreshold = 70
	if (totalInUse > diskInUseCleanupThreshold) {
		return true
	} else {
		return false
	}
}

def nextAvailableJenkinsBuilder () {
	def allNodes = jenkins.model.Jenkins.instance.nodes
	def x86TargetNode
	for (node in allNodes) {
		if (node.nodeName == "godzilla" && node.getComputer().isOnline() && !node.getComputer().countBusy()) {
			x86TargetNode = node.nodeName
		}
	}
	String x86TargetNextBuilder = x86TargetNode ?: generalProperties.x86TargetNextBuilder
	return x86TargetNextBuilder
}

String prodOrDebugFolders (boolean debugRun = false) {
	if (debugBuild () || debugRun) {
		echo "This is a releng testing run. Will run builds on ${branchProperties.relengDebugCalledBuildsDir}"
		return branchProperties.relengDebugCalledBuildsDir
	} else {
		return branchProperties.calledBuildsDir
	}
}

boolean debugBuild() {
	if (JOB_NAME.contains(generalProperties.relengJobPath) || JOB_NAME.contains(generalProperties.byoJobPath)) {
		echo "This is a debug build or byo (running from debug folder)"
		return true
	} else {
		echo "This is a production build (not running from debug folder)"
		return false
	}
}

boolean stringInCurrentLog (String string2Search) {
	boolean found = false
	def numberOfLogLinesForTerminationSearch = generalProperties.numberOfLogLinesForTerminationSearch.toInteger()
	def logText = currentBuild.rawBuild.getLog(numberOfLogLinesForTerminationSearch)
	if (logText.contains(string2Search)) {
		echo "$string2Search was found in current log"
		found = true
	} else {
		echo "Could not find $string2Search in current log"
	}
	return found
}

boolean runningOnAwsInstance() {
	env.internalAwsIP = generalProperties.internalAwsIP
	return sh(script: "curl http://$internalAwsIP/latest/meta-data/instance-id | grep '^i-[0-9]*' ", returnStatus: true) == 0
}

boolean releaseProcess() {
	boolean releaseProcess = false
	echo "Checking if this build started by $branchProperties.fullPathPackageReleaseJob or by $branchProperties.fullPathNextMachineImageJob"
	currentBuild.upstreamBuilds?.each{ upstreamBuild ->
		readAbleBuildName = upstreamBuild.getFullProjectName()
		echo "upstream build: $readAbleBuildName"
		if (readAbleBuildName.matches(branchProperties.fullPathPackageReleaseJob) || readAbleBuildName.matches(branchProperties.fullPathNextMachineImageJob)) {
			echo "This is a release process build"
			releaseProcess = true
		}
	}
	return releaseProcess
}

def setQAJobParameters () {
	String qaJobProvisionType = branchProperties.qaSpotProvisionType
	String qaNodesPostBehaviorType = branchProperties.qaNodesDailyPostBehavior
	if (releaseProcess()) {
		qaJobProvisionType = branchProperties.qaOnDemandProvisionType
		qaNodesPostBehaviorType = branchProperties.qaNodesReleasePostBehaviorKeep
	}
	echo "qaJobProvisionType: $qaJobProvisionType, qaNodesPostBehaviorType: $qaNodesPostBehaviorType"
	return [ qaJobProvisionType, qaNodesPostBehaviorType ]
}

def isSpotTermination (String lastStage = env.STAGE_NAME) {
	try {
		echo "Last stage: |$lastStage|"
		sh """
			echo 'lastStage=$lastStage' >> $WORKSPACE/$generalProperties.jobSummaryFile
			echo 'result=$currentBuild.currentResult' >> $WORKSPACE/$generalProperties.jobSummaryFile
		"""
		artifact.publishArtifactsStatus(generalProperties.jobSummaryFile, WORKSPACE)
	} catch (java.io.IOException e) {
		echo "Spot termination. Writing job description"
		currentBuild.description = "spot termination"
		error("Spot termination")
	} catch (error) {
		echo "Other error: |$error|"
	}
}

def mergeFiles(Map args) {
	/*
	Merge a list of stashed  / local files to one.
	If a file to merge exists locally, no need to unstash it.

	Parameters:
	boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	String (mandatory): targetFile - Name for the merged file
	def (mandatory): filesToMerge - List of files to unstash and merge to target
	*/
	general.traceFunctionParams ("jenkins.mergeFiles", args)
	general.errorMissingMandatoryParam ("jenkins.mergeFiles",
		[	filesToMerge: "$args.filesToMerge",
			targetFile: "$args.targetFile",
		])

	boolean dryRun = args.dryRun ?: false
	String targetFile = args.targetFile
	def filesToMerge = args.filesToMerge
	String tmpFile = "tmp.txt"
	general.runOrDryRunSh (false, "rm -rf $tmpFile", "Remove tmp file, to avoid any garbage if exists")

	if (filesToMerge) {
		filesToMerge.each { fileToMerge ->
			def files = findFiles glob: "$fileToMerge"
			boolean exists = files.length > 0
			if (!exists) {
				unstash(name: fileToMerge)
			}
			general.runOrDryRunSh (dryRun, "cat $fileToMerge >> $tmpFile", "Cat file on tmp target")
		}

		general.runOrDryRunSh (false, "cat $tmpFile | sort | uniq > $targetFile", "Uniq the target file to avoid duplicate lines if any")
		general.runOrDryRunSh (dryRun, "cat $targetFile", "Cat the target file to log")

	} else {
		echo "List of files to merge is empty, nothing to merge and publish."
	}
}

def setUnstableAsError (String jobTitle = "${env.JOB_NAME} [${env.BUILD_NUMBER}]") {
	if (currentBuild.result == 'UNSTABLE') {
		currentBuild.result = 'FAILURE'
		currentBuild.description = "Unstable"
		mail (
			to: "releng@scylladb.com",
			subject: "$jobTitle - UNTABLE to ERROR",
			body: "$jobTitle Finished as UNSTABLE and set to ERROR. Please check why."
		)
	}
}

def getApiToken(String username) {
    user = hudson.model.User.get(username)
    prop = user.getProperty(jenkins.security.ApiTokenProperty.class)
    return username
}

return this
