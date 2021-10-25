def doCheckout (String gitURL, String branch, boolean disableSubmodulesParam = false) {
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

def remoteBranchSha(String repoURL, String branch) {
	if (shaValue(branch)) {
		return branch
	}
	String lastShaOnBranch = ""
	sshagent(["github-promoter"]) {
		lastShaOnBranch = sh(script: "git ls-remote --heads $repoURL $branch | awk '{print \$1}'", returnStdout: true).trim()
	}
	echo "Last SHA of repo: |$repoURL|, branch: |$branch|: |$lastShaOnBranch|"
	return lastShaOnBranch
}

def checkoutToDir(String gitURL, String branch, String checkoutDir = WORKSPACE, boolean disableSubmodulesParam = false) {
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
