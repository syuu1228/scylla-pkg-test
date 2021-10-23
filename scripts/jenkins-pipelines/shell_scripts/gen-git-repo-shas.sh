#!/bin/bash

set -e
PROGRAM=$(basename $0)
EXIT_STATUS=0

function usage {
  echo "Usage: $PROGRAM --base_dir=<dir to work in> --branch=branch-<nn.nn> --qa_branch=master --qa_repo_list=<qarepo1,qarepo2..> --repo_list=<repo1,repo2..> --properties_file=<file name> --git_url=<base git url>"
  echo ""
  echo "    --base_dir=<dir to work in>"
  echo "    --branch=brnach-<nn.nn>       e.g. branch-3.0 or branch-2018.1"
  echo "    --qa_branch=master            e.g. master"
  echo "    --qa_repo_list                Comma seperated list of QA repos to get sha's of"
  echo "    --repo_list                   Comma separated list of repos to get sha's of"
  echo "    --properties_file             File name to write properties in"
  echo "    --git_url                     base git url"
}

DIR=$(dirname $(readlink -f $0))
source $DIR/sh-utils.sh

for i in "$@"
do
case $i in
    --base_dir*)
    BASE_DIR="${i#*=}"
    shift
    ;;
    --branch*)
    BRANCH="${i#*=}"
    shift
    ;;
    --qa_branch*)
    QA_BRANCH="${i#*=}"
    shift
    ;;
    --repo_list*)
    REPO_LIST="${i#*=}"
    shift
    ;;
    --qa_repo_list*)
    QA_REPO_LIST="${i#*=}"
    shift
    ;;
    --properties_file*)
    PROPERTIES_FILE="${i#*=}"
    shift
    ;;
    --git_url*)
    GIT_URL="${i#*=}"
    shift
    ;;
    *)
    echo "Error: unknown command line option: |$i|"
    usage
    exit 1
    ;;
esac
done

echo "$PROGRAM got these Parameters:"
echo "   --base_dir         = \"$BASE_DIR\""
echo "   --branch           = \"$BRANCH\""
echo "   --qa_branch        = \"$QA_BRANCH\""
echo "   --repo_list        = \"$REPO_LIST\""
echo "   --qa_repo_list     = \"$QA_REPO_LIST\""
echo "   --properties_file  = \"$PROPERTIES_FILE\""
echo "   --git_url          = \"$GIT_URL\""
echo "=================="

fail_if_param_missing "$BASE_DIR" '--base_dir'
fail_if_param_missing "$BRANCH" '--branch'
fail_if_param_missing "$QA_BRANCH" '--qa_branch'
fail_if_param_missing "$REPO_LIST" '--repo_list'
fail_if_param_missing "$QA_REPO_LIST" '--qa_repo_list'
fail_if_param_missing "$PROPERTIES_FILE" '--properties_file'
fail_if_param_missing "$GIT_URL" '--git_url'

cd $BASE_DIR
echo "Create env file. pwd is:"
pwd

rm -f $PROPERTIES_FILE
touch $PROPERTIES_FILE

# Check if a comma-separated list contains a matching entry.
#
# Arguments:
#  list		A comma-separated list as a string.
#  match	The match to look for in the list.
#
# Returns:
#   true if match is found in list; false otherwise
list_contains () {
	local list=$1
	local match=$2

	IFS=',' read -ra entries <<< "$list"

	for entry in ${entries[@]}; do
		[[ $entry = $match ]]  && return
	done

	false
}

IFS=',' read -ra REPOS <<< "$REPO_LIST"
for repo in "${REPOS[@]}"; do
	branch_jenkins_property=${repo//-/_}  ## branch names on sha's file should be with _ (as they are on property names on jenkins), while on git they are with -.
	if [[ "$repo" == *"enterprise"* ]]; then
		branch_jenkins_property=${branch_jenkins_property//scylla_enterprise/scylla}
	fi

	if list_contains $QA_REPO_LIST $repo; then
		GIT_REV_RESULT=`git ls-remote --heads ${GIT_URL}${repo}.git $QA_BRANCH | awk '{print $1;}'`
	else
		GIT_REV_RESULT=`git ls-remote --heads ${GIT_URL}${repo}.git $BRANCH | awk '{print $1;}'`
	fi

	echo "git head for |$repo|: Jenkins property name: ${branch_jenkins_property}_branch |$GIT_REV_RESULT|"
	if [[ "x$GIT_REV_RESULT" = "x" ]]; then
		echo "Error - Could not retrive repo SHA"
		EXIT_STATUS=1
	fi

  echo ${branch_jenkins_property}_branch=$GIT_REV_RESULT >> $PROPERTIES_FILE

done

echo "Repo's SHAs properties file $PROPERTIES_FILE: ----------------"
cat $PROPERTIES_FILE
echo "--------------------------"
exit $EXIT_STATUS
