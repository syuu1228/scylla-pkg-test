def doTest (Map args) {
	// Run Scylla tests, and publish results
	//
	// Parameters:
	// boolean dryRun (default: false) - show commands rather than executing them.
	// String numOfRepeat: (defaults 1) - number of times to repeat individual test
	// String testMode (default: release) - which mode to test: release|debug|dev
	// String (default: null) includeTests - which tests to run. Default: null
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability
	//
	// Returns:
	// list of: [ errorFound (boolean, true if errors were found), numberOfFails, testResultSummary, testResultDetails ]

	general.traceFunctionParams ("test.doTest", args)

	String numOfRepeats = args.numOfRepeats ?: 1
	String testMode = args.testMode ?: release
	boolean dryRun = args.dryRun ?: false
	String includeTests = args.includeTests ?: ""
	String architecture = args.architecture ?: "x86_64"
	architectureForFilePath = "${architecture}_"

	boolean publishFailed = false
	boolean testsFailed = false
	int numberOfFails = 0
	String testResultSummary = "${architectureForFilePath}${testMode}_unittest_results="
	String testResultDetails = "${architectureForFilePath}$testMode unittest results details: "

	String testArtifactsPath = "$WORKSPACE/$gitProperties.scyllaCheckoutDir"
	String pyLogFile = "$generalProperties.testsFilesDir/$generalProperties.pyTestLogFile"
	String testsPath = "${generalProperties.testsFilesDir}/$testMode"
	String architectureModeTestsPath = "${generalProperties.testsFilesDir}/${architectureForFilePath}$testMode"
	String junitTestXmlFile = "$testsPath/xml/${generalProperties.junitTestXmlFile}"
	String xunitTestXmlFile = "$testsPath/xml/${generalProperties.xunitTestXmlFile}"
	String testOutputFile = "output_test_${architectureForFilePath}${testMode}.txt"
	String architectureModePyLogFile = "${generalProperties.testsFilesDir}/${architectureForFilePath}${generalProperties.pyTestLogFile}"
	String architectureModeXunitTestXmlFile = "$architectureModeTestsPath/xml/${generalProperties.xunitTestXmlFile}"
	String architectureModeJunitTestXmlFile = "$architectureModeTestsPath/xml/${generalProperties.junitTestXmlFile}"
	String logFiles = "$testsPath/${generalProperties.testsLogFiles}"
	String architectureModeLogFiles = "$architectureModeTestsPath/${generalProperties.testsLogFiles}"

	dir("$gitProperties.scyllaCheckoutDir") {
		sh '''
			if ls jenkins_test* 1> /dev/null 2>&1; then
				echo "Removing old tests jenkins_test*"
				rm -Rf jenkins_test*
			fi
		'''

		String testCommand = "./test.py --mode=${testMode} --verbose --repeat $numOfRepeats $includeTests"
		try {
			general.runOrDryRunSh (dryRun, "bash -c 'set -o pipefail; ./tools/toolchain/dbuild -- $testCommand 2>&1 | tee $testOutputFile'", "Testing Scylla in $testMode mode")
		} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interruptEx) {
			currentBuild.result = 'ABORTED'
			error ("Test $testMode aborted. Aborting the job. Error: |$interruptEx|")
		} catch (error) {
			testsFailed = true
			currentBuild.result = 'FAILURE'
			echo "Error: test $testMode failed, error: |$error|."
			general.runOrDryRunSh (dryRun, "mv $testsPath $architectureModeTestsPath", "Move log files to architecture folder")
			publishFailed |= artifact.publishArtifactsStatus(architectureModeLogFiles, testArtifactsPath)
		} finally {
		    if (! testsFailed) {
		        general.runOrDryRunSh (dryRun, "mv $testsPath $architectureModeTestsPath", "Move log files to architecture folder")
		    }
			echo "Uploading artifacts for mode $testMode architecture: |$architecture|"
 			general.runOrDryRunSh (dryRun, "mv $testArtifactsPath/$pyLogFile $testArtifactsPath/$architectureModePyLogFile", "Rename test py log files to include architecture")

			boolean needToPublish = (!dryRun)
			publishFailed |= publishTestResults(needToPublish, architectureModeXunitTestXmlFile, "xunit", testArtifactsPath)
			publishFailed |= publishTestResults(needToPublish, architectureModeXunitTestXmlFile, "junit", testArtifactsPath)
			publishFailed |= publishTestResults(needToPublish, architectureModeJunitTestXmlFile, "junit", testArtifactsPath)
			if (! dryRun) {
				publishFailed |= artifact.publishArtifactsStatus(architectureModeXunitTestXmlFile, testArtifactsPath)
				publishFailed |= artifact.publishArtifactsStatus(architectureModeJunitTestXmlFile, testArtifactsPath)
				publishFailed |= artifact.publishArtifactsStatus(testOutputFile, testArtifactsPath)
				publishFailed |= artifact.publishArtifactsStatus(architectureModePyLogFile, testArtifactsPath)
			}
		}

		(testsFailed, numberOfFails, testResultSummary, testResultDetails) = analyzeTestResults (
			dryRun: dryRun,
			testMode: testMode,
			resultsFile: testOutputFile,
			architecture: architecture,
		)

		// When only part of the tests are requested, we don't want to fail on a missing artifact, as not all are available
		publishFailed = publishFailed && !includeTests
		if (publishFailed) {
			testResultSummary += " Failed to publish some test artifact(s) architecture: $architecture"
		}
		echo "doTest going to return:"
		echo "testsFailed: $testsFailed || publishFailed: $publishFailed"
		echo "numberOfFails: $numberOfFails"
		echo "testResultSummary: $testResultSummary"
		echo "testResultDetails: $testResultDetails"
		return [ testsFailed || publishFailed, numberOfFails, testResultSummary, testResultDetails ]
	}
}

def testModes (Map args) {
	// Run Scylla tests and publish results on all needed modes
	//
	// Parameters:
	// boolean dryRun (default: false) - show commands rather than executing them.
	// String numOfRepeat: (defaults 1) - number of times to repeat individual test
	// String modes2Test (mandatory) - List of test modes
	// String (default: null) includeTests - which tests to run. Default: null
	// String (default x86_64): architecture Which architecture to publish x86_64|aarch64
	// String (default generalProperties.jobSummaryFile) - jobSummaryFile File name to write result line on (as property).
	//
	// Returns:
	// list of: [ numberOfTestedModes, testsFailed, failedTestModes, allModesNumberOfTestFails, numberOfFailingModes, allTestResultDetails ]

	general.traceFunctionParams ("test.testModes", args)
	general.errorMissingMandatoryParam ("test.testModes", [modes2Test: "$args.modes2Test"])

	String numOfRepeats = args.numOfRepeats ?: 1
	String architecture = args.architecture ?: generalProperties.x86ArchName
	boolean dryRun = args.dryRun ?: false
	String includeTests = args.includeTests ?: ""
	String jobSummaryFile = args.jobSummaryFile ?: generalProperties.jobSummaryFile
	jobSummaryFile = "$WORKSPACE/$jobSummaryFile"

	boolean testsFailed = false
	int allModesNumberOfTestFails = 0
	int numberOfFailingModes = 0
	String failedTestModes = ""
	int numberOfTestedModes = 0
	String allTestResultDetails = ""

	if (numOfRepeats != "0" ) {
		args.modes2Test.each { testMode ->
			boolean modeTestsFailed = false
			int numberOfModeTestFails = 0
			String modeTestResultSummary = ""
			String modeTestResultDetails = ""

			numberOfTestedModes ++
			try {
				(modeTestsFailed, numberOfModeTestFails, modeTestResultSummary, modeTestResultDetails) = test.doTest (
					dryRun: dryRun,
					testMode: testMode,
					includeTests: includeTests,
					numOfRepeats: numOfRepeats,
					architecture: architecture,
				)

			} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interruptEx) {
				currentBuild.result = 'ABORTED'
				sh "echo '${architecture}${testMode}_unittest_results=Aborted' >> $jobSummaryFile"
				error ("Interrupt exception (abort) while Test phase, error: |$interruptEx|")
			} finally {
				if (currentBuild.currentResult == 'ABORTED') {
					sh "echo '${architecture}${testMode}_unittest_results=Aborted' >> $jobSummaryFile"
					error ("Interrupt exception (abort) while Test phase")
				} else {
					sh "echo '$modeTestResultSummary' >> $jobSummaryFile"
					if (modeTestsFailed) {
						testsFailed = true
						allModesNumberOfTestFails = allModesNumberOfTestFails + numberOfModeTestFails
						allTestResultDetails = "$allTestResultDetails\n$modeTestResultDetails"
						numberOfFailingModes ++
						if (!failedTestModes) {
							failedTestModes = "Architecture: $architecture failed test modes: $testMode"
						} else {
							failedTestModes = "$failedTestModes, $testMode"
						}
					}
				}
			}
		}
	}
	echo "testModes going to return:"
	echo "numberOfTestedModes: $numberOfTestedModes"
	echo "testsFailed: $testsFailed"
	echo "failedTestModes: $failedTestModes"
	echo "allModesNumberOfTestFails: $allModesNumberOfTestFails"
	echo "numberOfFailingModes: $numberOfFailingModes"
	echo "allTestResultDetails: $allTestResultDetails"
	return [ numberOfTestedModes, testsFailed, failedTestModes, allModesNumberOfTestFails, numberOfFailingModes, allTestResultDetails ]
}

def prepareDtestLocalTree (Map args) {
	// Checkout and Get artifacts needed to run dtest
	//
	// Parameters:
	// boolean (default false): preserveWorkspace -
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (default: gitProperties.scyllaPkgRepoUrl): relengRepo - Repository to checkout scylla-pkg from
	// String (default: branchProperties.stableBranchName): relengBranch - scylla-pkg branch
	// String (default: branchProperties.stableBranchName): scyllaBranch - scylla branch
	// String (default: branchProperties.stableBranchName): dtestBranch - dtest branch
	// String (default: branchProperties.stableBranchName): ccmBranch - ccm branch
	//
	// string (default null): artifactWebUrl - From where to get the artifact (when you run on a cloud machine)
	// String (default none): artifactSourceJob - Name of job to get artifacts from, on local machine
	// String (default null): artifactSourceJobNum - From where to get the artifact (when you run on a local machine)
	// String (default: release) buildMode - release|debug
	// Boolean (defaulf: false) isSpotInstanceMachine - True for parallel dtest jobs, false for other none parallel dtest
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability


	general.traceFunctionParams ("test.prepareDtestLocalTree", args)

	boolean preserveWorkspace = args.preserveWorkspace ?: false
	String relengRepo = args.relengRepo ?: gitProperties.scyllaPkgRepoUrl
	String relengBranch = args.relengBranch ?: branchProperties.stableBranchName
	String scyllaBranch = args.scyllaBranch ?: branchProperties.stableBranchName
	String dtestBranch = args.dtestBranch ?: branchProperties.stableBranchName
	String ccmBranch = args.ccmBranch ?: branchProperties.stableBranchName
	String artifactWebUrl = args.artifactWebUrl ?: ""
	String artifactSourceJob = args.artifactSourceJob ?: ""
	String artifactSourceJobNum = args.artifactSourceJobNum ?: ""
	String buildMode = args.buildMode ?: "release"
	boolean isSpotInstanceMachine = args.isSpotInstanceMachine ?: false
	String architecture = args.architecture ?: ""
	boolean dryRun = args.dryRun ?: false

	def boolean downloadFromCloud = true
	def mandatoryArgs = general.setMandatoryList (downloadFromCloud, [artifactWebUrl: "$args.artifactWebUrl"], [artifactSourceJob: "$args.artifactSourceJob"])
	general.errorMissingMandatoryParam ("test.prepareDtestLocalTree", mandatoryArgs)

	if (isSpotInstanceMachine) {
		echo "No need for cleaning workspace"
	} else {
		git.cleanWorkSpaceUponRequest(preserveWorkspace)
	}

	boolean disableSubmodules = true // for pkg. Does not work without it. We need it to run dtest.sh script.
	git.checkoutToDir (relengRepo, relengBranch, gitProperties.scyllaPkgCheckoutDir, disableSubmodules)
	git.checkoutToDir (gitProperties.scyllaDtestRepoUrl, dtestBranch, gitProperties.scyllaDtestCheckoutDir)
	git.checkoutToDir (gitProperties.scyllaCcmRepoUrl, ccmBranch, gitProperties.scyllaCcmCheckoutDir)
	artifact.getArtifact(artifact: generalProperties.buildMetadataFile,
		targetPath: WORKSPACE,
		artifactSourceJob: artifactSourceJob,
		artifactSourceJobNum: artifactSourceJobNum,
		downloadFromCloud: downloadFromCloud,
		sourceUrl: artifactWebUrl,
		ignoreMissingArtifact: false
	)
	artifact.getRelocArtifacts (
		buildMode: buildMode,
		cloudUrl: artifactWebUrl,
	)
	artifact.publishMetadataFile ()

	echo "dtest will run based on relocatable package. Info: ============="
	sh "cat ${generalProperties.buildMetadataFile}"
	echo "============================"

	test.setupTestEnv(buildMode, architecture, dryRun)
	general.lsPath(WORKSPACE)
}

def analyzeTestResults (Map args) {
	// Analyze results of test mode, which failed, how many,
	//
	// Parameters:
	// boolean dryRun (default: false) - show commands rather than executing them.
	// String testMode (mandatory) - which mode to test: release|debug|dev
	// String (mandatory) resultsFile - results file to analyze..
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability
	//
	// Returns:
	// list of: [ errorFound (boolean, true if errors were found), numberOfFails, testResultSummary, testResultDetails ]

	general.traceFunctionParams ("test.analyzeTestResults", args)
	general.errorMissingMandatoryParam ("test.analyzeTestResults",
		[testMode: "$args.testMode",
		 resultsFile: "$args.resultsFile"])

	boolean dryRun = args.dryRun ?: false
	String architecture = args.architecture ?: "x86_64"
	architecture = "${architecture}_"

	String error2Grep="The following test(s) have failed:"
	String summaryLine2Grep="Summary:" // Example line: "Summary: 6 of the total 411 tests failed"
	boolean errorFound = false
	String testResultDetails = "$args.testMode $architecture unittest results details: "
	String testResultSummary = "${args.testMode}_${architecture}unittest_results="
	String grepResult = ""
	String grepSummaryResult = ""
	int numberOfFails=0

	if (dryRun) {
		testResultDetails += "dryRun"
		testResultSummary += "dryRun"
	} else {
		if (fileExists ("$args.resultsFile")) {
			try {
				grepResult = sh(returnStdout: true, script: "grep '${error2Grep}' $args.resultsFile").trim()
			} catch (error) {
				echo "No errors were found on log. (Catch of $error2Grep grep result. The grep 'error': $error)"
			} finally {
				if (grepResult) {
					errorFound = true
					publishFailingTestExe (args.testMode, grepResult)
					try {
						grepSummaryResult = sh(returnStdout: true, script: "grep '${summaryLine2Grep}' $args.resultsFile").trim()
					} catch (error) {
						echo "No summary found on log. (catch of $summaryLine2Grep grep result. The grep 'error': $error (happens when tests abort)"
						String errorText ="Failure for unknown reason, no summary line found on test log ($args.resultsFile under build artifacts). Usually due to test abortion."
						testResultSummary += errorText
						testResultDetails += errorText
					} finally {
						if (grepSummaryResult) {
							testResultSummary += "$grepSummaryResult"
							def matches = grepSummaryResult =~ /\s*(\d+)\.*/
							String errors = matches[0][1]
							numberOfFails = errors.toInteger()
						}
						testResultDetails += "\n------------------\nfailing test(s):\n${grepResult}\n------------------\n"
					}
				} else {
					testResultSummary += "No errors were found."
					testResultDetails += "No errors were found."
				}
			}
		} else {
			testResultSummary += "Could not find $args.resultsFile to analyze"
			testResultDetails += "Could not find $args.resultsFile to analyze"
		}
	}
	echo "analyzeTestResults for testMode: $args.testMode $architecture going to return:"
	echo "errorFound: $errorFound"
	echo "numberOfFails: $numberOfFails"
	echo "testResultSummary: $testResultSummary"
	echo "testResultDetails: $testResultDetails"
	return [ errorFound, numberOfFails, testResultSummary, testResultDetails ]
}

def publishFailingTestExe (String testMode, String failingTests) {
	echo "publishFailingTestExe params: failingTests: |$failingTests|, testMode: |$testMode|"
	failingTests = failingTests.substring(failingTests.indexOf(":") + 1 , failingTests.length());
	String boostPath = "${gitProperties.scyllaCheckoutDir}/build/$testMode/test/"
	String testExePath = "${gitProperties.scyllaCheckoutDir}/build/$testMode/FailingtestExe/"

	ArrayList testNames = failingTests.split(' ')
	sh ("mkdir -p $WORKSPACE/${testExePath}")
	testNames.each { testName ->
		testName.trim()
		if (testName) {
			echo "publish test exe for |$testName|"
			sh ("cp $WORKSPACE/$boostPath${testName} $WORKSPACE/${testExePath}. || echo 'Could not find |$WORKSPACE/$boostPath${testName}| ignoring'")
		}
	}
	artifact.publishArtifactsStatus("${testExePath}*", WORKSPACE)
}

boolean isflakyUnittest (Map args) {
	// Analyze results of test in all modes: is the failure flaky?
	// Mail developers if yes.
	// Flaky definition in this function:
	// If number of tested modes is more than number of failing tests

	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (mandatory) testFailed: True if errors were found on tests
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competible
	// String (mandatory) numberOfTestedModes: Number of tested modes.
	// String (mandatory) numberOfTestFails: How many tests failed (in all modes)
	// String (mandatory) numberOfFailingModes: Now many modes failed.
	// String (default: null) allTestResultDetails: Details of failing tests (for mail)
	// String (mandatory) jobTitle: For mail subject
	// String (mandatory) devAddress: mail address
	// String (default generalProperties.jobSummaryFile) - jobSummaryFile File name to write result line on (as property).

	boolean dryRun = args.dryRun ?: false
	String allTestResultDetails = args.allTestResultDetails ?: ""
	String architecture = args.architecture ?: ""
	String jobSummaryFile = args.jobSummaryFile ?: generalProperties.jobSummaryFile
	jobSummaryFile = "$WORKSPACE/$jobSummaryFile"
	general.traceFunctionParams ("test.isflakyUnittest", args)
	boolean isflaky = false
	if (dryRun) {
		return isflaky
	}
	general.errorMissingMandatoryParam ("test.isflakyUnittest",
		[testFailed: "$args.testFailed",
		numberOfTestedModes: "$args.numberOfTestedModes",
		numberOfTestFails: "$args.numberOfTestFails",
		numberOfFailingModes: "$args.numberOfFailingModes",
		jobTitle: "$args.jobTitle",
		devAddress: "$args.devAddress"])

	allTestResultDetails = "Check console output at ${env.BUILD_URL}/consoleText " + allTestResultDetails

	if (args.testFailed && args.numberOfTestedModes > args.numberOfTestFails) {
		isflaky = true
		echo "Unittest failure looks flaky. architecture: $architecture"
		mail.mailResults(args.devAddress, "${args.jobTitle}: $architecture Unittest failure looks flaky", allTestResultDetails)
	}
	sh "echo 'isFlakyUnitTests=$isflaky' >> $jobSummaryFile"
	return isflaky
}

def microBenchMarks (boolean dryRun = false) {
	def exists = fileExists "${gitProperties.scyllaCheckoutDir}/perf_fast_forward_output"
	if (exists) {
		general.runOrDryRunSh (dryRun, "rm -rf ${gitProperties.scyllaCheckoutDir}/perf_fast_forward_output", "Removing old microBenchMarks (perf_fast_forward_output) output files")
	} else {
		echo "No old microBenchMarks (perf_fast_forward_output) output files ${gitProperties.scyllaCheckoutDir}/perf_fast_forward_output. Nothing to remove"
	}

	exists = fileExists "${gitProperties.scyllaCheckoutDir}/perf_large_partition_data"
	if (exists) {
		general.runOrDryRunSh (dryRun, "rm -rf ${gitProperties.scyllaCheckoutDir}/perf_large_partition_data", "Removing old perf_large_partition_data")
	} else {
		echo "No old perf_large_partition_data files ${gitProperties.scyllaCheckoutDir}/perf_large_partition_data. Nothing to remove"
	}

	String perfFastForwardCmd1 = "${generalProperties.microbenchmarksTestExe} --populate -c1"
	String perfFastForwardCmd2 = "${generalProperties.microbenchmarksTestExe} -c1 --enable-cache --output-format=json"
	dir("${gitProperties.scyllaCheckoutDir}") {
		general.runOrDryRunSh (dryRun, "./tools/toolchain/dbuild /bin/bash -c '${perfFastForwardCmd1}; ${perfFastForwardCmd2}'", "Running microBenchMarks")
	}
}

def microBenchMarksAnalyzer (boolean dryRun = false, String eMailTo = generalProperties.devMail, boolean debugBuild = false) {
	String updateDBParam = "--update-db"
	if (debugBuild) {
		updateDBParam = ""
	}
	dir("$WORKSPACE/${generalProperties.scyllaClusterTestsCheckoutDir}") {
		general.runOrDryRunSh (dryRun, "cp -r $WORKSPACE/${gitProperties.scyllaCheckoutDir}/perf_fast_forward_output $WORKSPACE/${generalProperties.scyllaClusterTestsCheckoutDir}", "Running microBenchMarks analyzer")
		general.runOrDryRunSh (dryRun, "./docker/env/hydra.sh 'python3 sdcm/microbenchmarking.py check --results-path . $updateDBParam --email-recipients ${eMailTo}'", "Running microBenchMarks check")
	}
}

def doDtest (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): dtestDebugInfoFlag - Set to run with debug info
	// boolean (default false): dtestKeepLogsFlag - Set to keep logs
	// String (default null) excludeTests: List of tests to exclude.
	// String (default null) includeTests: List of tests to include.
	// String (default null) extOpts: Any set of external dtest options.
	// String (default null) extEnv: Any set of env settings
	// String (default null) randomDtests: Number of tested modes.
	// String (default null) randomDtestsSeed:
	// String (default null) dtestRepeats: How many times to repeat the dtests.
	// String (default null) testRunner: nosetest|pytest
	// String (mandatory) dtestMode release|debug
	// String (mandatory): dtestType - Use to generate logs based on type of tests. option: full|long|heavy|gating
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("test.doDtest", args)
	general.errorMissingMandatoryParam ("test.doDtest", [dtestMode: "$args.dtestMode", dtestType: "$args.dtestType"])

	boolean dryRun = args.dryRun ?: false
	boolean dtestDebugInfoFlag = args.dtestDebugInfoFlag ?: false
	boolean dtestKeepLogsFlag = args.dtestKeepLogsFlag ?: false
	String excludeTests = args.excludeTests ?: ""
	String includeTests = args.includeTests ?: ""
	String extOpts = args.extOpts ?: ""
	String extEnv = args.extEnv ?: ""
	String randomDtests = args.randomDtests ?: ""
	String randomDtestsSeed = args.randomDtestsSeed ?: ""
	String dtestRepeats = args.dtestRepeats ?: "1"
	String testRunner = args.testRunner ?: ""
	String architecture = args.architecture ?: ""

	echo "Calling dtest in docker toolchain"
	String dtestRunTestSh = "$WORKSPACE/${gitProperties.scyllaDtestCheckoutDir}/scripts/run_test.sh"
	String dtestScript = "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.pipelinesShellPath}/dtest.sh"
	String dtestParameters = setDtestParams (
			dryRun: dryRun,
			dtestMode: args.dtestMode,
			dtestDebugInfoFlag: dtestDebugInfoFlag,
			dtestKeepLogsFlag: dtestKeepLogsFlag,
			excludeTests: excludeTests,
			includeTests: includeTests,
			extOpts: extOpts,
			extEnv: extEnv,
			randomDtests: randomDtests,
			randomDtestsSeed: randomDtestsSeed,
			dtestRepeats: dtestRepeats,
			testRunner: testRunner,
			dtestType: args.dtestType,
			architecture: architecture,
		)

	boolean dtestFailed = false
	boolean publishFailed = false
	boolean needToPublish = ( ! dryRun)

	env.NODE_INDEX = generalProperties.smpNumber

	try {
		dir(WORKSPACE) {
			boolean dtestRunTestShExists = fileExists "$dtestRunTestSh"
			if (dtestRunTestShExists) {
				sh "set -o pipefail; $dtestScript $dtestParameters 2>&1 | tee output_dtest.txt"
			} else {
				sh "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/tools/toolchain/dbuild --network=bridge -- /bin/bash -c 'set -o pipefail; $dtestScript $dtestParameters 2>&1 | tee output_dtest.txt'"
			}
		}
	} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interruptEx) {
		currentBuild.result = 'ABORTED'
		error("Interrupt exception (abort) while dtest phase, error: |$interruptEx|")
	} catch (error) {
		if (currentBuild.currentResult == 'ABORTED') {
			error("Interrupt exception (abort) while dtest phase, error: |$error|")
		} else {
			dtestFailed = true
			echo "Error: dtest phase failed, going ahead to check tests and dtest status, error: |$error|"
			currentBuild.result = 'FAILURE'
		}
	} finally {
		if (needToPublish) {
			publishFailed |= artifact.publishArtifactsStatus("scylla-dtest.${args.dtestType}.${args.dtestMode}.${NODE_INDEX}*.xml", WORKSPACE)
			publishFailed |= artifact.publishArtifactsStatus("**/logs-${args.dtestType}.${args.dtestMode}.${NODE_INDEX}/**/*", gitProperties.scyllaDtestCheckoutDir)
			publishFailed |= test.publishTestResults(needToPublish, "scylla-dtest.${args.dtestType}.${args.dtestMode}.${NODE_INDEX}*.xml", "junit", WORKSPACE)
		}
	}

	String errorDescription = "dtest phase"
	if (publishFailed) {
		errorDescription = "Failed to publish some dtest artifact(s)"
	}
	if (dtestFailed) {
		errorDescription = "dtest failed"
	}
	boolean failedStatus = dtestFailed || publishFailed
	general.raiseErrorOnFailureStatus (failedStatus, errorDescription)
}

def collectDtestList (String includeTests, String excludeTests, String dtestType) {
	echo "collectDtestList: parameters includeTests: |$includeTests| ,excludeTests: |$excludeTests| and dtestType: |$dtestType|"

	dir ("$WORKSPACE/${gitProperties.scyllaDtestCheckoutDir}") {
		sh (script: "./scripts/run_test.sh --collect-only $includeTests $excludeTests --processes=0 --with-id --id-file $dtestType-${generalProperties.dtestCollectedFileName}", returnStdout: true)
		artifact.publishArtifactsStatus("${gitProperties.scyllaDtestCheckoutDir}/$dtestType-${generalProperties.dtestCollectedFileName}", WORKSPACE)
	}
}

def splitAndCopyDtestJobs (Map args) {
	// Parameters:
	// Integer (defaults: 45): splitTimeTarget -Time period (minutes) for a test group to run. Used to calculate the needed number of spot machines
	// Integer (defaults: 55): splitMaxNodes - Maximum number of nodes we can use for the job in parallel
	// String (default: release): buildMode release|debug
	// String (default: full): dtestType - Use to generate logs based on type of tests. option: full|long|heavy

	general.traceFunctionParams ("test.splitAndCopyDtestJobs(Split test based on jenkins job history)", args)

	int splitTimeTarget = args.splitTimeTarget ?: 45
	int splitMaxNodes = args.splitMaxNodes ?: 55
	String buildMode = args.buildMode ?: "release"
	String dtestType = args.dtestType ?: "full"

	String releaseFolder = branchProperties.calledBuildsDir.substring(1) //Remove first "/" from string
	String jobName = "${releaseFolder}dtest-$buildMode"
	env.JENKINS_URL = generalProperties.jenkinsUrl

	withCredentials([usernamePassword(credentialsId: 'jenkins-api-token', passwordVariable: 'JENKINS_TOKEN_PASSWORD', usernameVariable: 'JENKINS_TOKEN_USER')]) {
		dir ("$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}") {
			sh "mkdir -p $HOME/.local"
			dpackagerCommand = generalProperties.dpackagerPath
			dpackagerCommand += ' -e JENKINS_TOKEN_USER -e JENKINS_TOKEN_PASSWORD -e JENKINS_URL'
			dpackagerCommand += " -v $WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/scripts/jenkins-pipelines/python_scripts:/scripts"
			dpackagerCommand += " -v $WORKSPACE/${gitProperties.scyllaDtestCheckoutDir}/${dtestType}-${generalProperties.dtestCollectedFileName}:/scripts/${dtestType}-${generalProperties.dtestCollectedFileName}"
			dpackagerCommand += " -v $HOME/.local:$HOME/.local"
			sh "$dpackagerCommand -- python3 ${generalProperties.pipelinesPythonPath}/split_test.py \
							--collected-test-list-ids /scripts/${dtestType}-${generalProperties.dtestCollectedFileName} \
							--split-time ${splitTimeTarget} \
							--split-limit ${splitMaxNodes} \
							--job-name ${jobName} \
							--dtest-type ${dtestType}"
		}

		int numOfSplitFiles = sh(returnStdout: true, script: "ls $WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${dtestType}-include_* -1 | wc -l") as Integer

		echo "copy splits files into dtest folder"
		sh "cp $WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${dtestType}-include_* ${gitProperties.scyllaDtestCheckoutDir}/"
		echo "Number Of Split Files:$numOfSplitFiles"
		stash(name: "${dtestType}-dtest-split-files", includes:"${gitProperties.scyllaDtestCheckoutDir}/${dtestType}-include_*", useDefaultExcludes: false)

		return numOfSplitFiles
	}
}

def doParallelDtest (Map args) {

	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): dtestDebugInfoFlag - Set to run with debug info
	// boolean (default false): dtestKeepLogsFlag - Set to keep logs
	// String (default null) extOpts: Any set of external dtest options.
	// String (default null) extEnv: Any set of env settings
	// String (mandatory) numOfSplitFiles: Number of dtest groups
	// String (mandatory) dtestMode release|debug
	// String (default null) testRunner: nosetest|pytest
	// boolean (default false): downloadFromCloud - Whether to publish to cloud storage (S3) or not.
	// String (mandatory): cloudUrl - Needed if downloadFromCloud is true. From where to download the artifacts.
	// String (mandatory): runningUserID - show which user triggered the job
	// string (default git@github.com:scylladb/scylla-pkg.git): relengRepo
	// String (default stableBranch): relengBranch
	// String (default stableBranch): ccmBranch
	// String (default stableBranch): dtestBranch
	// String (default: full): dtestType - Use to generate logs based on type of tests. option: full|long|heavy
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("test.doParallelDtest", args)
	general.errorMissingMandatoryParam ("test.doParallelDtest", [dtestMode: "$args.dtestMode", cloudUrl: "$args.cloudUrl"])

	boolean dryRun = args.dryRun ?: false
	boolean dtestDebugInfoFlag = args.dtestDebugInfoFlag ?: false
	boolean dtestKeepLogsFlag = args.dtestKeepLogsFlag ?: false
	String excludeTests = args.excludeTests ?: ""
	String extOpts = args.extOpts ?: ""
	String extEnv = args.extEnv ?: ""
	String testRunner = args.testRunner ?: ""
	String dtestType = args.dtestType ?: "full"
	String architecture = args.architecture ?: ""

	def branches = [:]
	def runnersLabel =  args.splitFleetLabal ?: generalProperties.targetDtestBuilder
	boolean dtestFailed = false
	boolean publishFailed = false
	int numOfSplitFiles = args.numOfSplitFiles
	if (dryRun) {
		numOfSplitFiles = 1
	}

	withCredentials([usernamePassword(credentialsId: 'jenkins-api-token', passwordVariable: 'JENKINS_TOKEN_PASSWORD', usernameVariable: 'JENKINS_TOKEN_USER')]) {

		for (int i = 0; i < numOfSplitFiles; i++) {

			String nodeIndex = i
			nodeIndex = nodeIndex.padLeft(3, '0')
			branches["${dtestType}-split${nodeIndex}"] = {
				node(runnersLabel) {
					jenkins.checkAndTagAwsInstance(args.runningUserID)
					withEnv(["NODE_TOTAL=${numOfSplitFiles}", "NODE_INDEX=${nodeIndex}"]) {
						prepareDtestLocalTree (
							dryRun: dryRun,
							preserveWorkspace: false,
							relengRepo: args.relengRepo,
							relengBranch: args.relengBranch,
							dtestBranch: args.dtestBranch,
							ccmBranch: args.ccmBranch,
							artifactWebUrl: args.cloudUrl,
							buildMode: args.dtestMode,
							isSpotInstanceMachine: true,
							architecture: architecture,
						)

						def currentWorkSpace = sh(returnStdout: true, script: 'echo $WORKSPACE').trim()
						def instanceType = sh(returnStdout: true, script: "curl http://${generalProperties.internalAwsIP}/latest/meta-data/instance-type").trim()
						echo "instanceType: ${instanceType}"

						unstash(name: "${dtestType}-dtest-split-files")
						setupTestEnv(args.dtestMode, architecture, dryRun)
						String splitFileName = "$WORKSPACE/$gitProperties.scyllaDtestCheckoutDir/${dtestType}-include_${NODE_INDEX}.txt"
						if (! fileExists(splitFileName)) {
							error("split file missing - ${splitFileName}")
						}
						String localIncludeTests = sh(returnStdout: true, script: " cat ${splitFileName} | tr '\n' ' '").trim()
						String dtestRunTestSh = "$WORKSPACE/${gitProperties.scyllaDtestCheckoutDir}/scripts/run_test.sh"
						String dtestParameters = setDtestParams (
							dryRun: dryRun,
							dtestMode: args.dtestMode,
							dtestDebugInfoFlag: dtestDebugInfoFlag,
							dtestKeepLogsFlag: dtestKeepLogsFlag,
							includeTests: localIncludeTests,
							extOpts: extOpts,
							extEnv: extEnv,
							dtestType: dtestType,
						)

						String dtestScript = "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/scripts/jenkins-pipelines/shell_scripts/dtest.sh"
						echo "dtestParameters: |${dtestParameters}|"
						try {
							sh "set -o pipefail; ${dtestScript} ${dtestParameters} 2>&1 | tee output_dtest_${dtestType}_${NODE_INDEX}.txt"
						} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interruptEx) {
							currentBuild.result = 'ABORTED'
							error("Interrupt exception (abort) while dtest phase, error: |$interruptEx|")
						} catch (error) {
							if (currentBuild.currentResult == 'ABORTED') {
								error("Interrupt exception (abort) while dtest phase, error: |$error|")
							}
							else {
								echo "Error: dtest phase failed, going ahead to check tests and dtest status, error: |$error|"
								currentBuild.result = 'FAILURE'
							}
						}
						finally {
							if (!dryRun) {
								publishFailed |= artifact.publishArtifactsStatus("scylla-dtest.${dtestType}.${args.dtestMode}.${NODE_INDEX}*.xml", WORKSPACE)
								publishFailed |= artifact.publishArtifactsStatus("**/logs-${dtestType}.${args.dtestMode}.${NODE_INDEX}/**/*", gitProperties.scyllaDtestCheckoutDir)
								publishFailed |= publishTestResults(true, "scylla-dtest.${dtestType}.${args.dtestMode}.${NODE_INDEX}*.xml", "junit", WORKSPACE)
							}
						}
					}
				}
			}
		}
		parallel branches
	}
}

String setDtestParams (Map args) {
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): dtestDebugInfoFlag - Set to run with debug info
	// boolean (default false): dtestKeepLogsFlag - Set to keep logs
	// String (default null) excludeTests: List of tests to exclude.
	// String (default null) includeTests: List of tests to include.
	// String (default null) extOpts: Any set of external dtest options.
	// String (default null) extEnv: Any set of env settings
	// String (default null) randomDtests: Number of tested modes.
	// String (default null) randomDtestsSeed:
	// String (default null) dtestRepeats: How many times to repeat the dtests.
	// String (default null) testRunner: nosetest|pytest
	// String (mandatory) dtestMode release|debug
	// String (default: full): dtestType - Use to generate logs based on type of tests. option: full|long|heavy
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("test.setDtestParams", args)
	general.errorMissingMandatoryParam ("test.setDtestParams", [dtestMode: "$args.dtestMode"])

	boolean dryRun = args.dryRun ?: false
	boolean dtestDebugInfoFlag = args.dtestDebugInfoFlag ?: false
	boolean dtestKeepLogsFlag = args.dtestKeepLogsFlag ?: false
	String excludeTests = args.excludeTests ?: ""
	String includeTests = args.includeTests ?: ""
	String extOpts = args.extOpts ?: ""
	String extEnv = args.extEnv ?: ""
	String randomDtests = args.randomDtests ?: ""
	String randomDtestsSeed = args.randomDtestsSeed ?: ""
	String dtestRepeats = args.dtestRepeats ?: "1"
	String testRunner = args.testRunner ?: ""
	String dtestType = args.dtestType ?: "full"
	String architecture = args.architecture ?: ""

	String dtestParameters = "--home=$WORKSPACE/${gitProperties.scyllaCheckoutDir}"
	dtestParameters += " --mode=$args.dtestMode"
	dtestParameters += " --smp=$generalProperties.smpNumber"
	dtestParameters += " --exclude=\"$excludeTests\""
	dtestParameters += " --include=\"$includeTests\""
	dtestParameters += " --dtest-type=\"$dtestType\""

	if (dryRun) {
		dtestParameters = dtestParameters + " --dry_run"
	}
	if (dtestDebugInfoFlag) {
		dtestParameters = dtestParameters + " --debug"
	}

	if (dtestKeepLogsFlag) {
		dtestParameters = dtestParameters + " --keep_logs"
	}
	if (extOpts != "") {
		dtestParameters = dtestParameters + " --scylla_ext_opts=\"$extOpts\""
	}
	if (extEnv != "") {
		dtestParameters = dtestParameters + " --scylla_ext_env=\"$extEnv\""
	}

	if (randomDtests != "") {
		dtestParameters = dtestParameters + " --random=\"$randomDtests\""
		if (randomDtestsSeed != "") {
			dtestParameters = dtestParameters + " --random_seed=\"$randomDtestsSeed\""
		}
	}

	if (testRunner != "") {
		dtestParameters = dtestParameters + " --test_runner=\"$testRunner\""
	}

	if (dtestRepeats != "1") {
		dtestParameters = dtestParameters + " --repeat=\"$dtestRepeats\""
	}

	setupTestEnv(args.dtestMode, architecture, dryRun)

	echo "dtestParameters: |$dtestParameters|"
	return "$dtestParameters"
}

def createEmptyDir(String path) {
	sh "rm -rf $path && mkdir -p $path"
}

def artifactScyllaVersion() {
	def versionFile = generalProperties.buildMetadataFile
	def scyllaSha = ""
	boolean versionFileExists = fileExists "${versionFile}"
	if (versionFileExists) {
		scyllaSha = sh(script: "awk '/scylladb\\/scylla(-enterprise)?\\.git/ { print \$NF }' ${generalProperties.buildMetadataFile}", returnStdout: true).trim()
	}
	echo "Version is: |$scyllaSha|"
	return scyllaSha
}

def setupTestEnv(String buildMode, String architecture="", boolean dryRun=false) {
	// This override of HOME as an empty dir is needed by ccm
	echo "Setting test environment, mode: |$buildMode|, architecture: |$architecture|"
	def homeDir="$WORKSPACE/cluster_home"
	createEmptyDir(homeDir)
	// First look for local built pacakge
	String scyllaPackageName = artifact.relocPackageName (
		dryRun: dryRun,
		checkLocal: true,
		mustExist: false,
		urlOrPath: "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/$buildMode/dist/tar",
		packagePrefix: branchProperties.productName,
		buildMode: buildMode,
		architecture: architecture,
	)
	// If not found - look where artifacts are downloaded
	if (! scyllaPackageName) {
		scyllaPackageName = artifact.relocPackageName (
			dryRun: dryRun,
			checkLocal: true,
			mustExist: true,
			urlOrPath: WORKSPACE,
			packagePrefix: branchProperties.productName,
			buildMode: buildMode,
			architecture: architecture,
		)
	}
	String scyllaRelocPkgFile = "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/$buildMode/dist/tar/${scyllaPackageName}"
	echo "Test will use package: $scyllaRelocPkgFile"
	boolean pkgFileExists = fileExists scyllaRelocPkgFile
	env.NODE_INDEX = generalProperties.smpNumber
	env.SCYLLA_VERSION = artifactScyllaVersion()
	if (!general.versionFormatOK(env.SCYLLA_VERSION)) {
		env.MAPPED_SCYLLA_VERSION = "999.99.0"
	}
	env.EVENT_LOOP_MANAGER = "asyncio"
	// Some tests need event loop, 'asyncio' is most tested, so let's use it
	env.SCYLLA_CORE_PACKAGE_NAME = scyllaPackageName
	env.SCYLLA_CORE_PACKAGE = scyllaRelocPkgFile
	env.SCYLLA_JAVA_TOOLS_PACKAGE = "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/$buildMode/dist/tar/${branchProperties.productName}-tools-package.tar.gz"
	env.SCYLLA_JMX_PACKAGE = "$WORKSPACE/${gitProperties.scyllaCheckoutDir}/build/$buildMode/dist/tar/${branchProperties.productName}-jmx-package.tar.gz"
	env.DTEST_REQUIRE = "${branchProperties.dtestRequireValue}" // Could be empty / not exist
}

def setupCassandraResourcesDir() {
	// This empty dir and ln are needed by ccm
	String cassandraResourcesDir="$WORKSPACE/${gitProperties.scyllaCheckoutDir}/resources"
	createEmptyDir(cassandraResourcesDir)
	sh "ln -s $WORKSPACE/${gitProperties.scyllaToolsJavaCheckoutDir} $cassandraResourcesDir/cassandra"
}

def doPythonDriverMatrixTest (Map args) {
	// Run the Python test upon different repos
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// string (mandatory): pythonDriverScyllaOrDatastaxCheckoutDir - Scylla or datastax checkout dir
	// string (mandatory): pythonDriverMatrixCheckoutDir - The general python driver matrix dir
	// String (mandatory): driverType - scylla || datastax
	// String (mandatory): pythonDriverVersions - Python driver versions to check
	// String (mandatory): cqlBinaryProtocols - CQL Binary Protocols
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("test.doCPPDriverMatrixTest", args)
	general.errorMissingMandatoryParam ("test.doCPPDriverMatrixTest",
		[pythonDriverScyllaOrDatastaxCheckoutDir: "$args.pythonDriverScyllaOrDatastaxCheckoutDir",
		 pythonDriverMatrixCheckoutDir: "$args.pythonDriverMatrixCheckoutDir",
 		 cqlBinaryProtocols: "$args.cqlBinaryProtocols",
		 driverType: "$args.driverType",
		 pythonDriverVersions: "$args.pythonDriverVersions",
		])

	boolean dryRun = args.dryRun ?: false
	String architecture = args.architecture ?: ""

	setupTestEnv("release", architecture, dryRun)
	String pythonParams = "python3 main.py $args.pythonDriverScyllaOrDatastaxCheckoutDir"
	pythonParams += " --driver-type $args.driverType"
	pythonParams += " --tests tests.integration.standard"
	pythonParams += " --versions $args.pythonDriverVersions"
	pythonParams += " --protocols $args.cqlBinaryProtocols"

	dir(args.pythonDriverMatrixCheckoutDir) {
		general.runOrDryRunSh (dryRun, "$args.pythonDriverMatrixCheckoutDir/scripts/run_test.sh $pythonParams", "Run Python Driver Matrix test")
	}
}

def doCPPDriverMatrixTest  (Map args) {
	// Run the CPP test upon different repos
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// string (mandatory): driverCheckoutDir - Scylla or datastax checkout dir
	// String (mandatory): driverType - scylla || datastax
	// String (mandatory): cppDriverVersions - CPP driver versions to check
	// String (mandatory): cqlCassandraVersion - CQL Cassandra version
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("test.doCPPDriverMatrixTest", args)
	general.errorMissingMandatoryParam ("test.doCPPDriverMatrixTest",
		[driverCheckoutDir: "$args.driverCheckoutDir",
		 driverType: "$args.driverType",
		 cppDriverVersions: "$args.cppDriverVersions",
		 cqlCassandraVersion: "$args.cqlCassandraVersion",
		])

	boolean dryRun = args.dryRun ?: false
	String webUrl = args.webUrl ?: ""
	String artifactSourceJobNum = args.artifactSourceJobNum ?: ""
	String scyllaBranch = args.scyllaBranch ?: branchProperties.stableBranchName
	String architecture = args.architecture ?: ""

	setupTestEnv("release", architecture, dryRun)
	String pythonParams = "python3 main.py $args.driverCheckoutDir $WORKSPACE/${gitProperties.scyllaCheckoutDir} --driver-type $args.driverType --versions $args.cppDriverVersions"
 	pythonParams += " --scylla-version ${env.SCYLLA_VERSION}"
 	pythonParams += " --summary-file $WORKSPACE/$gitProperties.cppDriverMatrixCheckoutDir/summary.log"
 	pythonParams += " --cql-cassandra-version $args.cqlCassandraVersion"
	dir("$WORKSPACE/$gitProperties.cppDriverMatrixCheckoutDir") {
		general.runOrDryRunSh (dryRun, "$WORKSPACE/$gitProperties.cppDriverMatrixCheckoutDir/scripts/run_test.sh $pythonParams", "Run CPP Driver Matrix test")
	}
}

def doJavaDriverMatrixTest(Map args) {
	// Run the Java test upon different repos
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// string (mandatory): datastaxJavaDriverCheckoutDir - Scylla or datastax checkout dir
	// String (mandatory): javaDriverVersions -
	// String (default null): architecture Which architecture to publish x86_64|aarch64 Default null is for backwards competability

	general.traceFunctionParams ("test.doJavaDriverMatrixTest", args)
	general.errorMissingMandatoryParam ("test.doJavaDriverMatrixTest",
		[datastaxJavaDriverCheckoutDir: "$args.datastaxJavaDriverCheckoutDir",
		 javaDriverVersions: "$args.javaDriverVersions",
		])

	boolean dryRun = args.dryRun ?: false
	String architecture = args.architecture ?: ""

	setupCassandraResourcesDir()
	setupTestEnv("release", architecture, dryRun)
	String pythonParams = "python3 main.py $WORKSPACE/$args.datastaxJavaDriverCheckoutDir "
	pythonParams += "--versions '$args.javaDriverVersions' --scylla-version ${env.SCYLLA_VERSION}"
	dir("$WORKSPACE/${gitProperties.scyllaJavaDriverMatrixCheckoutDir}") {
		general.runOrDryRunSh (dryRun, "$WORKSPACE/${gitProperties.scyllaJavaDriverMatrixCheckoutDir}/scripts/run_test.sh $pythonParams", "Run Java Driver Matrix test")
	}
}

boolean publishTestResults (boolean needToPublish = true, String testsWildcardFiles = generalProperties.testsFiles, String UnitsType = "xunit", String baseDir = WORKSPACE) {
	if (!(needToPublish)) {
		echo "Skipping Publish jUnit files, as user requested dry run / pipeline stopped before creating the artifacts."
		return false
	}
	String statusBeforePublish = currentBuild.result
	boolean dirExists = fileExists "$baseDir"
	if (!dirExists) {
		echo "Error: No xunit / junit to publish (no dir |$baseDir|)"
		return true
	}
	boolean status = false
	dir(baseDir) {
		def files = findFiles glob: "$testsWildcardFiles"
		boolean exists = files.length > 0
		if (exists) {
			echo "Going to publish xunit files: $testsWildcardFiles"
			if ( UnitsType == "xunit" ) {
				try {
					step([$class: 'XUnitPublisher',
						thresholds: [[$class: 'FailedThreshold', unstableThreshold: '1']],
						tools: [[$class: 'BoostTestJunitHudsonTestType', pattern: "$testsWildcardFiles"]]])
				} catch (error) {
					echo "Error: Could not publish xunit files: |$testsWildcardFiles|. Error: |$error|"
					status = true
				}
			} else if ( UnitsType == "junit" ) {
				try {
					junit testsWildcardFiles
				} catch (error) {
					echo "Error: Could not publish junit files: |$testsWildcardFiles|. Error: |$error|"
					status = true
				}
			} else {
				echo "Error: Unknown type to publish (xunit / junit)"
				status = true
			}
		} else {
			echo "Error: No xunit / junit file to publish. No |$testsWildcardFiles| under |$baseDir|"
			status = true
		}
	}
	// Jenkins sets status to UNSTABLE while publish testing on some cases.
	// We want it as FAILURE.
	if (currentBuild.result == 'UNSTABLE') {
		if (statusBeforePublish == 'UNSTABLE') {
			echo "Status was unstalbe before this publish, leave it as is."
		} else {
			echo "Status became unstable during this publish. Set it to error"
			currentBuild.result = 'FAILURE'
		}
	}
	return status
}

def runAmiTests (Map args) {
	// Parameters:
	// boolean (default false): waitForBuilds - set true to Wait for tests to finish
	// boolean (default false): failIfTestFailed - set true to fail if test fails
	// String (default branchProperties.qaSpotProvisionType):	qaJobProvisionType Type of instance provision. available options: spot_low_price and on_demand
	// String (default branchProperties.qaNodesDailyPostBehavior):	qaNodesPostBehaviorType What to do with node at the end (destroy / keep-on-failure)
	// String (mandatory) instanceTypeList - which instance types to test
	// String (mandatory jobName - Name of test job to run
	// String (mandatory) amiId - ID of AMI to test
	// String (default: branchProperties.ubuntuManagerAgentListUrl): managerAgentRepoListUrl - Ubuntu or Centos manager's repo/list URL such as branchProperties.centosManagerAgentRepoUrl or branchProperties.ubuntuManagerAgentListUrl

	general.traceFunctionParams ("test.runAmiTests", args)
	general.errorMissingMandatoryParam ("test.runAmiTests",
		[instanceTypeList: "$args.instanceTypeList",
		jobName: "$args.jobName",
		amiId: "$args.amiId",
		managerAgentRepoListUrl: "$args.managerAgentRepoListUrl",
		])

	boolean waitForBuilds = args.waitForBuilds ?: false
	boolean failIfTestFailed = args.failIfTestFailed ?: false
	String 	qaJobProvisionType = args.qaJobProvisionType ?: branchProperties.qaSpotProvisionType
	String 	qaNodesPostBehaviorType = args.qaNodesPostBehaviorType ?: branchProperties.qaNodesDailyPostBehavior

	def parallelInstanceTypes = [:]
	def awsInstancesList = args.instanceTypeList.split(' ')
	awsInstancesList.each { instanceType ->
		if (instanceType.isEmpty()) {
			echo "List of extended instance types to test are empty, Skipping"
		} else {
			echo "Running AMI test on instacne type: |$instanceType|"
			parallelInstanceTypes[instanceType] = { ->
				if (jenkins.jobEnabled(args.jobName)) {
					echo "Running AMI test job $args.jobName"
					def jobResults=build job: args.jobName,
					parameters: [
						string(name: 'scylla_ami_id', value: args.amiID),
						string(name: 'region_name', value: generalProperties.amiRegion),
						string(name: 'instance_type', value: instanceType),
						string(name: 'provision_type', value: qaJobProvisionType),
						string(name: 'post_behavior_db_nodes', value: qaNodesPostBehaviorType),
						string(name: 'scylla_mgmt_agent_repo', value: args.managerAgentRepoListUrl),
					],
					propagate: failIfTestFailed,
					wait: waitForBuilds
				}
			}
		}
	}
	parallel parallelInstanceTypes
}

def runGceImageTests (Map args){
	// Run gce image tests in parallel with various gce instance types , with parameters
	//
	//
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// boolean (default false): waitForLongBuilds - whether to wait for called builds.
	// boolean (default false): failIfCallFailed - Whether to fail if one of the valled builds fails.
	// String (mandatory): buildsToRun - comma separated list of builds to call
	// String (default: branchProperties.calledBuildsDir): calledBuildsDir in which dir (folder) on jenkins builds will run (releng-testing or production)
	// String (default: branchProperties.qaSpotProvisionType): qaJobProvisionType -Type of instance provision. available options: spot_low_price and on_demand
	// String (default: branchProperties.ubuntuManagerAgentListUrl): managerAgentRepoListUrl - Ubuntu or Centos manager's repo/list URL such as branchProperties.centosManagerAgentRepoUrl or branchProperties.ubuntuManagerAgentListUrl
	// String (default: branchProperties.qaNodesDailyPostBehavior): qaNodesPostBehaviorType - What to do with node at the end (destroy / keep-on-failure)
	// String (default: ""): gceImageDbUrl - Url to gce image with db)
	// String (default: branchProperties.gceTestInstanceTypes): instanceGceTypes - string with instance types where run  gce image tests)
	general.traceFunctionParams ("test.runGceImageTests", args)
	general.errorMissingMandatoryParam ("test.runGceImageTests",
		[buildsToRun: "$args.buildsToRun"])

	boolean dryRun = args.dryRun ?: false
	boolean waitForLongBuilds = args.waitForLongBuilds ?: false
	boolean failIfCallFailed = args.failIfCallFailed ?: false

	String calledBuildsDir = args.calledBuildsDir ?: branchProperties.calledBuildsDir
	String qaJobProvisionType = args.qaJobProvisionType ?: branchProperties.qaSpotProvisionType
	String managerAgentRepoListUrl = args.managerAgentRepoListUrl ?: branchProperties.ubuntuManagerAgentListUrl
	String qaNodesPostBehaviorType = args.qaNodesPostBehaviorType ?: branchProperties.qaNodesDailyPostBehavior

	String gceImageDbUrl = args.gceImageDbUrl ?: ""
	String instanceGceTypes = args.instanceGceTypes ?: ""

	def parallelInstanceTypes = [:]
	def gceInstancesList = args.instanceGceTypes.split(' ')

	gceInstancesList.each { instanceType ->
		if (instanceType.isEmpty()) {
			echo "List of extended instance types to test are empty, Skipping"
		} else {
			echo "Running GCE image test on instacne type: |$instanceType|"
			parallelInstanceTypes[instanceType] = { ->
				jenkins.runBuilds(dryRun: dryRun,
					  waitForLongBuilds: waitForLongBuilds,
					  failIfCallFailed: failIfCallFailed,
					  calledBuildsDir: calledBuildsDir,
					  buildsToRun: args.buildsToRun,
					  qaJobProvisionType: qaJobProvisionType,
					  managerAgentRepoListUrl: managerAgentRepoListUrl,
					  qaNodesPostBehaviorType: qaNodesPostBehaviorType,
					  gceImageDbUrl: gceImageDbUrl,
					  instaceTypeGCE: instanceType
				)
			}
		}
	}
	parallel parallelInstanceTypes


}

def runRollingUpgradeJobs (Map args) {
	// Run QA rolling upgrade jobs
	//
	// Parameters:
	// String (mandatory) rollingUpgradeList - list of rolling upgrade jobs to run
	// String (mandatory) scyllaRepoUrl - scylla repo url
	// String (default: branchProperties.qaSpotProvisionType) qaJobProvisionType Type of instance provision. available options: spot_low_price and on_demand
	// String (default: branchProperties.qaNodesDailyPostBehavior) qaNodesPostBehaviorType What to do with node at the end (destroy / keep-on-failure)
	// String (default: branchProperties.calledBuildsDir): calledBuildsDir in which dir (folder) on jenkins builds will run (releng-testing or production)

	general.traceFunctionParams ("test.runRollingUpgradeJobs", args)
	general.errorMissingMandatoryParam ("test.runRollingUpgradeJobs",
		[rollingUpgradeList: "$args.rollingUpgradeList",
		 scyllaRepoUrl: "$args.scyllaRepoUrl"])

	String qaJobProvisionType = args.qaJobProvisionType ?:  branchProperties.qaSpotProvisionType
	String qaNodesPostBehaviorType = args.qaNodesPostBehaviorType ?:  branchProperties.qaNodesDailyPostBehavior
	String calledBuildsDir = args.calledBuildsDir ?: branchProperties.calledBuildsDir

	def rollingUpgradeJobList = "${args.rollingUpgradeList}".split('\\,',-1)
	echo "List: $rollingUpgradeJobList"
	rollingUpgradeJobList.each { jobName ->
		String jobPath = "${calledBuildsDir}${branchProperties.rollingUpgradeJobsDir}${jobName}"
		echo "Job name to run: $jobName"
		if (jenkins.jobEnabled(jobPath)) {
			echo "Running Rolling upgrade job $jobPath"
			def jobResults=build job: jobPath,
			parameters: [
				string(name: 'new_scylla_repo', value: args.scyllaRepoUrl),
				string(name: 'provision_type', value: qaJobProvisionType),
				string(name: 'post_behavior_db_nodes', value: qaNodesPostBehaviorType),
				string(name: 'post_behavior_loader_nodes', value: qaNodesPostBehaviorType),
				string(name: 'post_behavior_monitor_nodes', value: qaNodesPostBehaviorType),
			],
			propagate: false,
			wait: false
		}
	}
}

return this
