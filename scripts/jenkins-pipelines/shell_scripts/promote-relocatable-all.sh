#!/bin/bash

set -e
DRY_RUN=false
PROGRAM=$(basename $0)
LOCAL_DOWNLOAD_DIR="build/tmp"
echo "Running $PROGRAM"

function usage {
	echo "usage: $PROGRAM   [--dry-run] set this to view the commands, not to run them"
	echo "			--stable-downloads-url url of downloads. Should be stable also when you test"
	echo "			--release-name=<Scylla version name such as 4.2"
	echo "			--product-name=<scylla or scylla-enterprise"
	echo "			[--unstable-centos-url=<url>] - The unstable URL for centos (promotion source)"
	echo "			[--stable-centos-url=<url>] - The stable URL for centos (promotion target)"
	echo "			[--unstable-unified-deb-url=<url>]  - The unstable URL for unified-deb (promotion source)"
	echo "			[--stable-unified-deb-url=<url>]  - The stable URL for unified-deb (promotion target)"
	echo "			[--unstable-reloc-url=<url>]  - The unstable URL for the relocatable package (promotion source)"
	echo "			[--stable-reloc-url=<url>]  - The stable URL for the relocatable package (promotion target)"
	echo "			[--reloc-version=<url>]  - for 3.3 and above"
	echo "			[--addresses_file=<file path>] - File to write promotion addresses"
}

DIR=$(dirname $(readlink -f $0))
source $DIR/sh-utils.sh

function backup_centos {
	REMOTE_PRODUCTION_REPO_S3=s3://$1
	echo "Backing up CentOS (stable) repository for $REMOTE_PRODUCTION_REPO_S3"
	$S3_SYNC_CMD $REMOTE_PRODUCTION_REPO_S3/ $REMOTE_PRODUCTION_REPO_S3/backup/ --exclude "*backup/*"
}

function list_centos_artifacts {
	unstable_centos_url=$1
	if [ ! -z "$unstable_centos_url" ] ; then
		echo "Centos artifacts from unstable url $unstable_centos_url:"
		# scylla also on enterprise
		aws s3 ls --recursive ${unstable_centos_url}scylla/x86_64/$PRODUCT_NAME-server
		aws s3 ls --recursive ${unstable_centos_url}scylla/noarch/$PRODUCT_NAME-jmx
		aws s3 ls --recursive ${unstable_centos_url}scylla/noarch/$PRODUCT_NAME-tools
	else
		echo "No url specified for centos, skipping list"
	fi
}

function list_unified_deb_artifacts {
	unstable_unified_deb_url=$1
	if [ ! -z "$unstable_unified_deb_url" ] ; then
		echo "unified-deb artifacts from url ${unstable_unified_deb_url}pool:"
		aws s3 ls --recursive ${unstable_unified_deb_url}pool
	else
		echo "No url specified for unified-deb, skipping list"
	fi
}

function list_reloc_artifacts {
	unstable_reloc_url=$1
	if [ ! -z "$unstable_reloc_url" ] ; then
		echo "relocatable artifacts from |$unstable_reloc_url|:"
		aws s3 ls $unstable_reloc_url | grep tar.gz
	else
		echo "No url specified for relocateable packages, skipping list"
	fi
}

function promote_centos_rpm {
	unstable_centos_url=$1
	stable_centos_url=$2
	if [ ! -z "$unstable_centos_url" ] ; then
		LOCAL_DIR=build/rpm/centos/$REPO
		UNSTABLE_CENTOS_REPO_S3=s3://$unstable_centos_url
		PRODUCTION_CENTOS_REPO_S3=s3://$stable_centos_url

		echo "Promoting CentOS repository for $REPO $BRANCH"

		echo "Clean local disk from potential leftovers"
		rm -rf $LOCAL_DIR

		echo "Promote CentOS step 1 - aws s3 sync to download the prod repository to unstable dir"
		$S3_SYNC_CMD $PRODUCTION_CENTOS_REPO_S3 $LOCAL_DIR

		echo "Promote CentOS Step 2 - aws s3 sync to download the unstable artifact (which we are about to promote)"
		echo "Running: $S3_SYNC_CMD ${UNSTABLE_CENTOS_REPO_S3}scylla/ $LOCAL_DIR"
		$S3_SYNC_CMD ${UNSTABLE_CENTOS_REPO_S3}scylla/ $LOCAL_DIR

		echo "Promote CentOS Step 3 - RPM repository commands to generate metadata"
		mkdir -p $LOCAL_DIR/x86_64
		run_cmd createrepo -v --deltas $LOCAL_DIR/x86_64
		mkdir -p $LOCAL_DIR/noarch
		run_cmd createrepo -v --deltas $LOCAL_DIR/noarch

		echo "Promote CentOS Step 4 - aws s3 sync to upload the new artifact and the newly generated metadata"
		echo "Running: $S3_SYNC_CMD $LOCAL_DIR $PRODUCTION_CENTOS_REPO_S3"
		$S3_SYNC_CMD $LOCAL_DIR $PRODUCTION_CENTOS_REPO_S3
		PACKAGE_STRING="CentOS RPM: $stable_centos_url"

		echo "Clean local disk at the end of promote_centos_rpm"
		rm -rf $LOCAL_DIR
	else
		echo "No artifact specified for centos, or illegal url |$unstable_centos_url|, skipping promote"
	fi
}

function download_unstable_debian_ubuntu_repo {
	# Download the unstable repo to local disk, for later promote.
	# For unified-deb we download once, and uploads a few - as needed.
	scylla_deb_s3_repo_full_path=$1
	echo "Download (cp) repo to local machine"
	rm -rf "$LOCAL_DOWNLOAD_DIR"
	mkdir -p "$LOCAL_DOWNLOAD_DIR"
	$S3_CP_CMD $scylla_deb_s3_repo_full_path "$LOCAL_DOWNLOAD_DIR" --recursive
}

function promote_debian_ubuntu_repo {
	stable_unified_deb=$1

	PRODUCTION_DEB_UBUNTU_REPO=${stable_unified_deb}$REPO
	PRODUCTION_DEB_UBUNTU_REPO_S3=s3://$PRODUCTION_DEB_UBUNTU_REPO
	PRODUCTION_DEB_UBUNTU_HTTP=http://s3.amazonaws.com/$PRODUCTION_DEB_UBUNTU_REPO

	COMPONENT_PARAM="main"
	DIST_PARAM="stable"

	run_cmd rm -rf ~/.aptly

	echo "instead of $S3_SYNC_CMD - download using aptly for $DIST_PARAM"
	if `curl --output /dev/null --silent --head --fail $PRODUCTION_DEB_UBUNTU_HTTP/dists/${DIST_PARAM}/Release`
	then
	  run_cmd aptly mirror create --ignore-signatures=true scylladb-c $PRODUCTION_DEB_UBUNTU_HTTP $DIST_PARAM $COMPONENT_PARAM
	  run_cmd aptly mirror update --ignore-signatures=true scylladb-c
	  run_cmd aptly snapshot create scylladb-c from mirror scylladb-c
	  snapshots="scylladb-c"
	else
	  snapshots=""
	fi

	run_cmd aptly repo create -gpg-provider=gpg -distribution=$DIST_PARAM -component=$COMPONENT_PARAM scylladb-n
	run_cmd aptly repo add -gpg-provider=gpg scylladb-n "$LOCAL_DOWNLOAD_DIR"
	run_cmd aptly snapshot create scylladb-n from repo scylladb-n
	snapshots="scylladb-n $snapshots"

	# merge old and new
	run_cmd aptly snapshot merge -gpg-provider=gpg --no-remove=true scylladb $snapshots

	# publish
	run_cmd aptly publish snapshot -gpg-provider=gpg -gpg-key=$SCYLLA_GPG_KEYID scylladb scylladb

	# upload to public site
	rm -rf tmp_repo
	mkdir tmp_repo
	cp -av ~/.aptly/public/scylladb/* tmp_repo
	$S3_SYNC_CMD --acl public-read tmp_repo $PRODUCTION_DEB_UBUNTU_REPO_S3
	PACKAGE_STRING="Unified-deb: $PRODUCTION_DEB_UBUNTU_REPO/"
	echo $PACKAGE_STRING >> $ADDRESSES_FILE
	echo $PACKAGE_STRING
}

function promote_unified_deb_repo {
	unstable_unified_deb_url=$1
	stable_unified_deb_url=$2

	if [ ! -z "$unstable_unified_deb_url" ] ; then
		# Download is done once for all debians and ubuntu's, as we use one unified-deb and publish it a few times - once for each OS
		download_unstable_debian_ubuntu_repo "s3://$unstable_unified_deb_url"
		promote_debian_ubuntu_repo $stable_unified_deb_url
		run_cmd rm -rf "$LOCAL_DOWNLOAD_DIR"
	else
		echo "No url specified for unified deb. skipping promote"
	fi
}

function promote_reloc_artifacts {
	unstable_reloc_url=$1
	stable_reloc_url=$2
	reloc_version=$3
	LOCAL_DIR=relocatable
	UNSTABLE_RELOC_REPO_S3=s3://$unstable_reloc_url
	SCYLLA_PRODUCTION_RELOC_S3_REPO="s3://$stable_reloc_url"

	echo "Promoting Relocatable repository for $REPO $BRANCH"

	# Clean local disk from potential leftovers
	run_cmd rm -rf $LOCAL_DIR

	echo "Promote Reloc Step 1 - aws s3 sync to download the unstable artifact (which we are about to promote) from $UNSTABLE_RELOC_REPO_S3 to $LOCAL_DIR"
	$S3_SYNC_CMD --exclude "*" --include "*.txt" --include "*.tar.gz" $UNSTABLE_RELOC_REPO_S3 $LOCAL_DIR

	echo "Promote Reloc Step 2 - Add version to the relocateable packages except those who already have"
	for file in $LOCAL_DIR/*.tar.gz; do
		echo "tar file: |$file|, version: |$reloc_version|"
		if [[ $file == *$reloc_version* ]]; then
			echo "No need to rename tar $file as it already contains the version ID"
		else
			run_cmd mv "$file" "${file%.tar.gz}-${reloc_version}.tar.gz"
		fi
	done
	for file in $LOCAL_DIR/*.txt; do
		run_cmd mv "$file" "${file%.txt}-${reloc_version}.txt"
	done

	echo "Promote Reloc Step 3 - aws s3 sync to upload the old and new artifacts from $LOCAL_DIR to $SCYLLA_PRODUCTION_RELOC_S3_REPO"
	$S3_SYNC_CMD $LOCAL_DIR $SCYLLA_PRODUCTION_RELOC_S3_REPO
	PACKAGE_STRING="Relocatable (offline installer) package: ${stable_reloc_url}${PRODUCT_NAME}-unified-package-${reloc_version}.tar.gz"
	echo $PACKAGE_STRING >> $ADDRESSES_FILE
	echo $PACKAGE_STRING

	# Clean local disk
	run_cmd rm -rf $LOCAL_DIR
}

for i in "$@"
do
case $i in
	 --release-name*)
	RELEASE_NAME="${i#*=}"
	shift
	;;
	--product-name*)
	PRODUCT_NAME="${i#*=}"
	shift
	;;
	--unstable-centos-url*)
	UNSTABLE_CENTOS_RPM_URL="${i#*=}"
	shift
	;;
	--stable-centos-url*)
	STABLE_CENTOS_RPM_URL="${i#*=}"
	shift
	;;
	--unstable-unified-deb-url*)
	UNSTABLE_UNIFIED_DEB_URL="${i#*=}"
	shift
	;;
	--stable-unified-deb-url*)
	STABLE_UNIFIED_DEB_URL="${i#*=}"
	shift
	;;
	--unstable-reloc-url*)
	UNSTABLE_RELOCATABLE_URL="${i#*=}"
	shift
	;;
	--stable-reloc-url*)
	STABLE_RELOCATABLE_URL="${i#*=}"
	shift
	;;
	--reloc-version*)
	RELOCATABLE_VERSION="${i#*=}"
	shift
	;;
	--dry-run)
	DRY_RUN=true
	shift
	;;
	--stable-downloads-url*)
	SCYLLA_STABLE_DOWNLOADS_URL="${i#*=}"
	shift
	;;
	--addresses_file*)
	ADDRESSES_FILE="${i#*=}"
	shift
	;;
	*)
	echo "error: unknown command line option: $i"
	usage
	exit 1
	;;
esac
done

echo "$PROGRAM got these Parameters:"
echo "   --release-name                  = \"$RELEASE_NAME\""
echo "   --product-name                  = \"$PRODUCT_NAME\""
echo "   --dry-run                       = \"$DRY_RUN\""
echo "   --stable-downloads-url          = \"$SCYLLA_STABLE_DOWNLOADS_URL\""
echo "   --addresses_file                = \"$ADDRESSES_FILE\""

fail_if_param_missing "$RELEASE_NAME" '--release-name'
fail_if_param_missing "$PRODUCT_NAME" '--product-name'
fail_if_param_missing "$SCYLLA_STABLE_DOWNLOADS_URL" '--stable-downloads-url'

REPO=scylladb-$RELEASE_NAME
BRANCH=branch-$RELEASE_NAME

UNSTABLE_CENTOS_RPM_URL=$(add_trailing_slash_if_missing "$UNSTABLE_CENTOS_RPM_URL")
STABLE_CENTOS_RPM_URL=$(add_trailing_slash_if_missing "$STABLE_CENTOS_RPM_URL")
UNSTABLE_UNIFIED_DEB_URL=$(add_trailing_slash_if_missing "$UNSTABLE_UNIFIED_DEB_URL")
STABLE_UNIFIED_DEB_URL=$(add_trailing_slash_if_missing "$STABLE_UNIFIED_DEB_URL")
UNSTABLE_RELOCATABLE_URL=$(add_trailing_slash_if_missing "$UNSTABLE_RELOCATABLE_URL")
STABLE_RELOCATABLE_URL=$(add_trailing_slash_if_missing "$STABLE_RELOCATABLE_URL")

echo "Repository:               |$REPO|"
echo "Branch:                   |$BRANCH|"
echo "Unstable CentOS url:      |$UNSTABLE_CENTOS_RPM_URL|"
echo "Stable CentOS url:        |$STABLE_CENTOS_RPM_URL|"
echo "Unstable Unified deb url: |$UNSTABLE_UNIFIED_DEB_URL|"
echo "Stable Unified deb url:   |$STABLE_UNIFIED_DEB_URL|"
echo "Unstable Relocatable URL: |$UNSTABLE_RELOCATABLE_URL|"
echo "Stable Relocatable URL:   |$STABLE_RELOCATABLE_URL|"
echo "Relocatable version:      |$RELOCATABLE_VERSION|"

if [ -z "$ADDRESSES_FILE" ] ; then
	ADDRESSES_FILE="promote_addresses.txt"
fi
#
# Prerequisites:
#
#
# List artifacts: This is to verify we have content to promote from unstable to stable
#
if [ ! -z "$UNSTABLE_CENTOS_RPM_URL" ] ; then
	list_centos_artifacts $UNSTABLE_CENTOS_RPM_URL
fi

if [ ! -z "$UNSTABLE_RELOCATABLE_URL" ] ; then
	list_reloc_artifacts "$UNSTABLE_RELOCATABLE_URL"
fi

if [ ! -z "$UNSTABLE_UNIFIED_DEB_URL" ] ; then
	list_unified_deb_artifacts "$UNSTABLE_UNIFIED_DEB_URL"
fi

if $DRY_RUN ; then
	echo "This is a dryrun build. Running aws s3 commands with --dryrun"
	S3_SYNC_CMD="aws s3 sync --dryrun"
	S3_CP_CMD="aws s3 cp --dryrun"
elif [[ $SCYLLA_STABLE_DOWNLOADS_URL == *"testing"* ]]; then
	echo "This is a testing build, on $SCYLLA_STABLE_DOWNLOADS_URL"
	S3_SYNC_CMD="aws s3 sync --only-show-errors"
	S3_CP_CMD="aws s3 cp --no-progress"
else
	echo "This is a not a testing build. Running quite aws commands."
	S3_SYNC_CMD="aws s3 sync --only-show-errors"
	S3_CP_CMD="aws s3 cp --no-progress"
fi

#
# Backup stable URL
#
if [ ! -z "$UNSTABLE_CENTOS_RPM_URL" ] ; then
	backup_centos $STABLE_CENTOS_RPM_URL
fi

#
# Promote:
#
if [ ! -z "$UNSTABLE_CENTOS_RPM_URL" ] ; then
	promote_centos_rpm $UNSTABLE_CENTOS_RPM_URL $STABLE_CENTOS_RPM_URL
fi

if [ ! -z "$UNSTABLE_UNIFIED_DEB_URL" ] ; then
	gpg --import $SCYLLA_GPG_PUBLIC_KEY
	gpg --import --allow-secret-key-import $SCYLLA_GPG_PRIVATE_KEY
	promote_unified_deb_repo "$UNSTABLE_UNIFIED_DEB_URL" "$STABLE_UNIFIED_DEB_URL"
fi

if [ ! -z "$UNSTABLE_RELOCATABLE_URL" ] ; then
	promote_reloc_artifacts "$UNSTABLE_RELOCATABLE_URL" "$STABLE_RELOCATABLE_URL" "$RELOCATABLE_VERSION"
fi

echo "$PROGRAM ended successfully"
