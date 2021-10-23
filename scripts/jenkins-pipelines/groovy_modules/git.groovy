def doCheckout (String gitURL = gitProperties.scyllaRepoUrl, String branch = branchProperties.stableBranchName, boolean disableSubmodulesParam = false) {
	println "doCheckout: Checkout git repo: |" + gitURL + "| branch: |$branch| pwd: |${pwd()}|, workspace: |$WORKSPACE| disable submodule param: |$disableSubmodulesParam| (should be true for pkg)"
	// mirror.git is updated daily with clones of all our repositories
	// by the git-mirror job
	def ref_repo = "${env.HOME}/mirror.git"
	if (!fileExists(ref_repo)) {
		ref_repo = ""
	}

	checkout([
		$class: 'GitSCM',
		branches: [[name: branch]],
		extensions: [[
			$class: 'SubmoduleOption',
			disableSubmodules: disableSubmodulesParam,
			parentCredentials: true,
			recursiveSubmodules: true,
			reference: ref_repo,
			timeout: 60,
			trackingSubmodules: false
		],[
			$class: 'CloneOption',
			reference: ref_repo,
			timeout: 60
		],[
			$class: 'CheckoutOption',
			timeout: 60
		]],
		submoduleCfg: [],
		userRemoteConfigs: [[
			url: gitURL,
			refspec: "+refs/heads/$branch:refs/remotes/origin/$branch",
			credentialsId: 'github-promoter'
		]]
	])
}

def useScyllaForkRepo (String forkRepoUrl, String forkRepoBranch, String baseDir) {
	dir(baseDir) {
		sshagent(['github-promoter']) {
			sh "git remote add fork $forkRepoUrl"
			sh "git fetch  --depth=1 fork $forkRepoBranch"
			sh "git checkout $forkRepoBranch"
		}
	}

}

boolean shaValue(String value) {
	if (value =~ /^[0-9a-f]+$/) {
		echo "|$value| is a sha"
		return true
	} else {
		echo "|$value| is not a sha"
		return false
	}
}

def remoteBranchSha(String repoURL, String branch) {
	if (shaValue(branch)) {
		return branch
	}
	String lastShaOnBranch = ""
	sshagent([generalProperties.gitUser]) {
		lastShaOnBranch = sh(script: "git ls-remote --heads $repoURL $branch | awk '{print \$1}'", returnStdout: true).trim()
	}
	echo "Last SHA of repo: |$repoURL|, branch: |$branch|: |$lastShaOnBranch|"
	return lastShaOnBranch
}

def checkoutToDir(String gitURL = gitProperties.scyllaRepoUrl, String branch = branchProperties.stableBranchName, String checkoutDir = WORKSPACE, boolean disableSubmodulesParam = false) {
	println "checkoutToDir: Checkout git repo: |" + gitURL + "| branch: |$branch| dir: |$checkoutDir| disableSubmodulesParam: |$disableSubmodulesParam|"
	boolean dirExists = fileExists checkoutDir
	if (! dirExists) {
		echo "Creating dir |${checkoutDir}| for checking out"
		sh "mkdir ${checkoutDir}"
	}

	def sha = ""
	dir (checkoutDir) {
		doCheckout(gitURL, branch, disableSubmodulesParam)
		sh "git status"
		if (shaValue(branch)) {
			sha = branch
		} else {
			sha = gitRevParse("origin/${branch}")
		}
		echo "Sha is: |$sha|"
		return sha
	}
}

String gitRevParse (String branch) {
	def sha = sh(returnStdout: true, script: "git rev-parse ${branch}").trim()
	return sha
}

def labelBackports(String scyllaNextSha, String scyllaMasterSha, boolean dryRun = false) {
	echo "Labeling backports from next SHA $scyllaNextSha to master/branch/stable SHA $scyllaMasterSha"

	if (dryRun) {
		echo "dryRun build, skipping backport labeling from $scyllaNextSha to $scyllaMasterSha"
	} else {
		build job: 'releng/scylla-backport-queue',
			parameters: [
				string(name: 'GIT_COMMIT_START', value: scyllaNextSha),
				string(name: 'GIT_COMMIT_END',   value: scyllaMasterSha)
			],
			wait: true,
			propagate: true
	}
}

def push2Git(String origBranch, String targetBranch, boolean dryRun = false, String baseDir = WORKSPACE) {
	dir(baseDir) {
		sshagent(['github-promoter']) {
			general.runOrDryRunSh (dryRun, "git push origin origin/${origBranch}:${targetBranch}", "push from $origBranch to $targetBranch on dir $baseDir")
		}
	}
}

def tagRepo(Map args){
	// Create a build metadatafile.
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (default: WORKSPACE): baseDir - Where to do the tag work
	// String (mandatory): versionId - ID for tag. Examples: 4.3.1 or 2020.1.3
	// String (mandatory): repoUrl - Repo to set tag on
	// String (mandatory): sha - sha to set tag from

	general.traceFunctionParams ("git.tagRepo", args)

	String baseDir = args.baseDir ?: WORSPACE
	boolean dryRun = args.dryRun ?: false

	general.errorMissingMandatoryParam ("git.tagRepo",
		[versionId: "$args.versionId",
		 repoUrl: "$args.repoUrl",
		 sha: "$args.sha"])

	String tag="scylla-$args.versionId"
	dir(baseDir) {
		sshagent(['github-promoter']) {
			if (args.repoUrl == gitProperties.scyllaRepoUrl) {
				echo "Tagging Scylla repo, no need to checkout"
			} else {
				boolean disableSubmodulesParam=true
				doCheckout(args.repoUrl, args.sha, disableSubmodulesParam)
			}
			// FixMe: Pekka wonders why this is needed. I remember I faced problems without it. Verify if can be done without it.
			configureUserMail(baseDir) // We need user email address for "git tag" commit/push.

			general.runOrDryRunSh (dryRun, "git tag $tag $args.sha", "Create tag for repo $args.repoUrl")
			general.runOrDryRunSh (dryRun, "git push origin $tag", "push the new tag")
		}
	}
}

def updateGitSubmodules (String gitURL = gitProperties.scyllaPkgRepoUrl, String branch = branchProperties.stableBranchName, String checkoutDir = WORKSPACE) {
	echo "updateGitSubmodules: Set package submodules. branch |$branch|, checkoutDir is |$checkoutDir|"
	boolean disableSubmodules = true

	dir (checkoutDir) {
		doCheckout(gitURL, branch, disableSubmodules)
		sshagent(['github-promoter']) {
			sh "git checkout $branch"
			sh "git submodule sync"
			sh "git submodule update --init --recursive --force"
		}
	}
}

def cleanWorkSpaceUponRequest(boolean preserveWorkSpace = false) {
	echo "Cleaning workspace |$WORKSPACE|, Node: |$NODE_NAME|"
	if (preserveWorkSpace) {
		echo "Keeping workspace due to user request"
	} else {
		def architecture = sh(returnStdout: true, script: "uname -m").trim()
		echo "architecture: |$architecture| (could be x86_64 or aarch64)"
		if (architecture.toString().contains("aarch64")) {
			echo "This is an ARM machine, can't use sudo to clean root files"
		} else {
			// In order to clean also root owned files, first change ownership
			// https://issues.jenkins-ci.org/browse/JENKINS-24440?focusedCommentId=357010&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-357010
			sh "sudo chmod -R 777 ."
		}
		cleanWs() /* clean up our workspace */
		// Clean docker images older then 2 weeks to save disk space. The which docker is to prevent error when no docker installed.
	}
}

def configureUserMail (String gitDir, String mail = generalProperties.gitUserMail, name = generalProperties.gitUserName) {
	dir (gitDir) {
		sh "git config user.email \"${mail}\""
		sh "git config user.name \"${name}\""
	}
}

def createGitProperties(String repoString = branchProperties.gitRepositories, String fileName = generalProperties.gitPropertiesFileName) {
	echo "Creating $fileName file"
	sh "touch $fileName"
	ArrayList repoList = repoString.split('\\,')
	repoList.each { repo ->
		ArrayList namePartsList = repo.split('\\-')
		def repo4PropertyName = namePartsList[0]
		for (i = 1; i < namePartsList.size(); i++) {
			if (!(namePartsList[i].contains("enterprise"))) {
				def capWord = namePartsList[i].capitalize()
				repo4PropertyName = "${repo4PropertyName}${capWord}"
			}
		}

		def repo4Dir = repo.replace("scylla-enterprise", "scylla")
		sh "echo \"${repo4PropertyName}RepoUrl=${generalProperties.gitBaseURL}${repo}.git\" >> ${fileName}"
		if (repo4Dir == "scylla-cassandra-unit-tests") {
			sh "echo \"${repo4PropertyName}CheckoutDir=cassandra-unit-tests\" >> ${fileName}"
		} else {
			sh "echo \"${repo4PropertyName}CheckoutDir=${repo4Dir}\" >> ${fileName}"
		}
	};
	//Support different place for scripts and release repositories
	sh "echo \"scyllaPkgScriptsCheckoutDir=scylla-pkg-scripts\" >> ${fileName}"
}

def createShaProperties (Map args) {
	// Create a build metadatafile.
	// Parameters:
	// boolean (default false): dryRun - Run builds on dry run (that will show commands instead of really run them).
	// String (default: WORKSPACE): baseDir - Dir to run git commands
	// String (default WORKSPACE/gitProperties.scyllaPkgCheckoutDir): shellScriptsDir - From where to run sh script
	// String (default: branchProperties.releaseGitRepositories): repoList - list of repositories
	// String (default: branchProperties.stableBranchName) - branch for non QA repo's
	// String (default: branchProperties.stableQABranchName): qaBranch - branch for QA repositories
	// String (default: branchProperties.qaGitRepositories): qaRepoList - List of QA repositories
	// String (mandatory) gitRepoShaFilePath = where to write results

	general.traceFunctionParams ("git.createShaProperties", args)

	String baseDir = args.baseDir ?: WORKSPACE
	String shellScriptsDir = args.shellScriptsDir ?: "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}"
	String repoList = args.repoList ?: branchProperties.releaseGitRepositories
	String qaBranch = args.qaBranch ?: branchProperties.stableQABranchName
	String qaRepoList = args.qaRepoList ?: branchProperties.qaGitRepositories
	String branch = args.branch ?: branchProperties.stableBranchName

	general.errorMissingMandatoryParam ("git.createShaProperties", [gitRepoShaFilePath: "$args.gitRepoShaFilePath"])

	// Call a script to set env vars for jobs that should run in Jenkins, publish the file and load properties
	sshagent(['github-promoter']) {
		String genShaParams = "--base_dir=$baseDir"
		genShaParams += " --repo_list=$repoList"
		genShaParams += " --qa_branch=$qaBranch"
		genShaParams += " --qa_repo_list=$qaRepoList"
		genShaParams += " --properties_file=$args.gitRepoShaFilePath"
		genShaParams += " --branch=$branch"
		genShaParams += " --git_url=$generalProperties.gitBaseURL"
		sh "$shellScriptsDir/gen-git-repo-shas.sh $genShaParams"
	}

	if (artifact.publishArtifactsStatus(generalProperties.gitRepoShaFileName, gitProperties.scyllaPkgCheckoutDir)) {
		error("Could not publish some item(s). See log for details")
	}

	// Define gitRepoShas as global, as other places use it
	gitRepoShas = readProperties file: "$WORKSPACE/${gitProperties.scyllaPkgCheckoutDir}/${generalProperties.gitRepoShaFileName}"
	echo "gitRepoShas vars are:"

	echo "scylla_branch: |${gitRepoShas.scylla_branch}|"
	echo "scylla_ccm_branch: |${gitRepoShas.scylla_ccm_branch}|"
	echo "scylla_dtest_branch: |${gitRepoShas.scylla_dtest_branch}|"
	echo "scylla_machine_image_branch: |${gitRepoShas.scylla_machine_image_branch}|"
	echo "cassandra_unit_tests_branch: |${gitRepoShas.cassandra_unit_tests_branch}|"
}

def createCheckoutShaProperties(Map args) {
	// Create SHA properties file.
	// Parameters:
	// String (default: WORKSPACE): baseDir - Dir to run git commands
	// String (null): pkgSha - SHA for pkg repo
	// String (null): scyllaSha - SHA for scylla repo
	// String (null): ccmSha - SHA for ccm repo
	// String (null): dtestSha - SHA for dtest repo

	general.traceFunctionParams ("git.createCheckoutShaProperties", args)

	String baseDir = args.baseDir ?: WORKSPACE

	artifactPath = "$baseDir/${generalProperties.checkoutGitRepoShaFileName}"
	sh """
		echo \"scylla_pkg_branch=${args.pkgSha}\" > $artifactPath
		echo \"scylla_branch=${args.scyllaSha}\" >> $artifactPath
		echo \"scylla_ccm_branch=${args.ccmSha}\" >> $artifactPath
		echo \"scylla_dtest_branch=${args.dtestSha}\" >> $artifactPath
	"""
	status = artifact.publishArtifactsStatus(generalProperties.checkoutGitRepoShaFileName, baseDir)
}

def createJobSummaryFiles (String jobSummaryFile, String scyllaBranchOrSha=branchProperties.nextBranchName, String branchName=branchProperties.nextBranchName) {
	String scyllaSha = scyllaBranchOrSha
	if (!shaValue(scyllaBranchOrSha)) {
		scyllaSha = remoteBranchSha(gitProperties.scyllaRepoUrl, scyllaSha)
	}
	String pkgSha    = remoteBranchSha(gitProperties.scyllaPkgRepoUrl, branchName)
	String dtestSha  = remoteBranchSha(gitProperties.scyllaDtestRepoUrl, branchName)
	String ccmSha    = remoteBranchSha(gitProperties.scyllaCcmRepoUrl, branchName)

	sh """
		echo 'jobName=$JOB_NAME' > $jobSummaryFile
		echo 'jobNumber=$BUILD_NUMBER' >> $jobSummaryFile
		echo 'workspace=${pwd()}' >> $jobSummaryFile
		echo 'nodeName=$NODE_NAME' >> $jobSummaryFile
		echo 'scyllaSha=$scyllaSha' >> $jobSummaryFile
		echo 'dtestSha=$dtestSha' >> $jobSummaryFile
		echo 'ccmSha=$ccmSha' >> $jobSummaryFile
		echo 'pkgSha=$pkgSha' >> $jobSummaryFile
	"""

	createCheckoutShaProperties (
		baseDir: WORKSPACE,
		scyllaSha: scyllaSha,
		pkgSha: pkgSha,
		dtestSha: dtestSha,
		ccmSha: ccmSha
	)
}

boolean sameShaOnRemoteBranches (String repoUrl, String branch1, String branch2) {
	String branch1Sha = remoteBranchSha(repoUrl, branch1)
	String branch2Sha = remoteBranchSha(repoUrl, branch2)
	if (branch1Sha == branch2Sha) {
		echo "SHAs of the 2 given brnaches are the same: $branch1Sha"
		return true
	} else {
		echo "SHAs of the 2 given brnaches are the not the same: $branch1: $branch1Sha, $branch2: $branch2Sha"
		return false
	}
}

return this
