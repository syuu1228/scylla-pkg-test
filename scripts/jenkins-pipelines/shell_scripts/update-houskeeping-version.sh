#!/bin/bash
set -e
PROGRAM=$(basename $0)
DRY_RUN=false
BASE_DIR=`pwd`
echo "Current dir is $BASE_DIR"

function usage {
  echo "Usage: $PROGRAM --full_version=<nn.nn.nn> --system=<scylla|enterprise> --sql_address=<Where to run update> [--dry_run]"
  echo ""
  echo "    --full_version=<nn.nn.nn version such as 3.2.0 2019.1.8>"
  echo "    --system=<scylla|enterprise>"
  echo "    --sql_address=<Where to run update. Usually: scylla-downloads-v2.cluster-cmibwi2oeyz8.us-west-2.rds.amazonaws.com"
  echo "    --dry_run Print commands instead of running them"
  echo "    SCYLLA_USER and SCYLLA_PASSWD should be defined on env when calling this script."
}

DIR=$(dirname $(readlink -f $0))
source $DIR/sh-utils.sh

for i in "$@"
do
case $i in
    --full_version*)
    full_version="${i#*=}"
    shift
    ;;
    --system*)
    system="${i#*=}"
    shift
    ;;
    --sql_address*)
    sql_address="${i#*=}"
    shift
    ;;
    --dry_run)
    DRY_RUN=true
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
echo "   --full_version = \"$full_version\""
echo "   --system       = \"$system\""
echo "   --sql_address  = \"$sql_address\""
echo "   --dry_run      = \"$DRY_RUN\""

split_scylla_version "$full_version"
release_name="${major_version}.${minor_version}"

echo "Version parts:"
echo "   major_version  = \"$major_version\""
echo "   minor_version  = \"$minor_version\""
echo "   patch_version  = \"$patch_version\""
echo "   release_name  = \"$release_name\""
echo "=================="

fail_if_param_missing "$full_version" '--full_version'
fail_if_param_missing "$system" '--system'
fail_if_param_missing "$sql_address" '--sql_address'
fail_if_param_missing "$SCYLLA_USER" 'env var for SCYLLA_USER'
fail_if_param_missing "$SCYLLA_PASSWD" 'env var for SCYLLA_PASSWD'

if [[ "$system" != "enterprise" ]] && [[ "$system" != "scylla" ]]; then
  echo "Error: --system value should be 'enterprise' of 'scylla', but got '$system' exiting..."
  echo ""
  usage
  exit 1
fi

rx='^([0-9]+\.){0,2}(\*|[0-9]+)$'
if [[ $full_version =~ $rx ]]; then
 echo "INFO: Version $full_version is OK"
else
 echo "INFO: Version '$full_version' is not of nn.nn.nn type. Might be an RC version. Skipping update housekeeping version."
 exit
fi

TEST_CURL_LINE="https://repositories.scylladb.com/scylla/check_version?sts=d&uu=${SCYLLA_USER}&rid=${SCYLLA_PASSWD}&version=${release_name}&rtype=centos"
echo "mysql version verification: $TEST_CURL_LINE"
mysql_version_line=`curl $TEST_CURL_LINE`
echo "mysql_version_line before update: |$mysql_version_line|"

mysql_update_command="mysql -h $sql_address -u $SCYLLA_USER -p$SCYLLA_PASSWD -e \"INSERT INTO housekeeping.versions (major, minor, patch, full, system) VALUES($major_version, $minor_version, $patch_version, '$full_version', '$system') ON DUPLICATE KEY update full=if(patch>values(patch), full, VALUES(full)), patch=GREATEST(patch, VALUES(patch));\""
if $DRY_RUN ; then
    echo "Dry-run: $mysql_update_command"
else
	echo "Running: Update mysql with new version: |$mysql_update_command|"
	mysql -h $sql_address -u $SCYLLA_USER -p$SCYLLA_PASSWD -e "INSERT INTO housekeeping.versions (major, minor, patch, full, system) VALUES($major_version, $minor_version, $patch_version, '$full_version', '$system') ON DUPLICATE KEY update full=if(patch>values(patch), full, VALUES(full)), patch=GREATEST(patch, VALUES(patch));"
	mysql_version_line=`curl $TEST_CURL_LINE`
	echo "mysql_version_line after update: |$mysql_version_line|"
	if [[ "$mysql_version_line" == *"$full_version"* ]]; then
		exit 0
	else
		echo "Error: version on housekeeping mysql |$mysql_version_line| does not match expected |$full_version|"
		exit 1
	fi
fi
