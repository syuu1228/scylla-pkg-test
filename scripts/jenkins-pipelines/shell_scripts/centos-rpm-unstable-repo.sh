#!/bin/sh
set -e
BUILD_ARM=false
echo "Create CentOS RPM temp repo script: $0"
echo "============== env: =============="
env
echo "=================================="
echo "Parameters via env (should not be empty):"
echo "artifacts_prefix: |$artifacts_prefix|. Options: scylla|scylla-enterprise|scylla-manager"
echo "s3_repo_path_prefix: |$s3_repo_path_prefix| Prefix of Address on cloud such as downloads.scylladb.com/unstable/scylla/<branch>/rpm/centos"
echo "x86_arch: |$x86_arch| default: x86_64"
echo "build_arm: |$build_arm| default: false or null or not set - so no arm will be built"
echo "build mode: |$build_mode| default: release"

if [ "x$build_arm" != "x" ]; then
	BUILD_ARM=true
fi

function error_if_not_found {
	item=$1
  find . -name "$item" | grep . || { echo "could not find any candidate for $item" ; RPM_NOT_FOUND=true; }
}

if [ "X$artifacts_prefix" == "X" ] || [ "X$s3_repo_path_prefix" == "X" ]; then
	die "$0: Missing mandatory parameter (as env var)"
fi
rm -Rf tmp_repo

if [[ $artifacts_prefix == *"manager"* ]]; then
	repo_name="scylla-manager"
	tmp_base="tmp_repo/scylla-manager"
else
	if [ $build_mode = "debug" ]; then
		repo_name="scylla-debug"
		tmp_base="tmp_repo/scylla-debug"
	else
		repo_name="scylla"
		tmp_base="tmp_repo/scylla"
	fi
fi
RPM_NOT_FOUND=false

if [ "x$x86_arch" = "x" ]; then x86_arch="x86_64"; fi
arm_arch="aarch64"
all_archs="noarch"
architectures=$x86_arch

if $BUILD_ARM ; then
	architectures="$x86_arch,$arm_arch"
	mkdir -pv $tmp_base/{$x86_arch,$arm_arch,$all_archs}
else
	mkdir -pv $tmp_base/{$x86_arch,$all_archs}
fi

if [[ $artifacts_prefix == *"manager"* ]]; then
	architecture_specific_rpm_names="server,client,agent"
else
	architecture_specific_rpm_names="conf,debuginfo,kernel-conf,node-exporter,python3"
	all_architectures_rpm_names="jmx,machine-image,tools,tools-core"
	# all archs
	for n in ${all_architectures_rpm_names//,/ }
	do
		echo "RPM: |$n|"
		error_if_not_found "${artifacts_prefix}-${n}-*.${all_archs}.rpm"
		find . -name "${artifacts_prefix}-${n}-*.${all_archs}.rpm" -exec cp '{}' $tmp_base/$all_archs ';' -print
	done
fi

# architecture specific
for a in ${architectures//,/ }
do
	echo "arch: |$a|"
	for i in ${architecture_specific_rpm_names//,/ }
	do
		error_if_not_found "${artifacts_prefix}-${i}-*.${a}.rpm"
	done
	find . -name "${artifacts_prefix}-*.${a}.rpm" -exec cp '{}' ${tmp_base}/${a} ';' -print
done

echo "===== $tmp_base/$all_archs dir after all copies ====="
ls $tmp_base/$all_archs
echo "===== $tmp_base/$x86_arch dir after all copies ====="
ls $tmp_base/$x86_arch
if $BUILD_ARM ; then
	echo "===== $tmp_base/$arm_arch dir after all copies ====="
	ls $tmp_base/$arm_arch
fi
echo "============="
if $RPM_NOT_FOUND ; then
  echo "Some RPM items were not found. Exiting."
  exit 1
fi

#ARM aarch64 (build if requested)
if $BUILD_ARM ; then
	for a in $tmp_base/{$x86_arch,$arm_arch,$all_archs} ; do createrepo -v --deltas $a/ ; done
elif [[ $artifacts_prefix == *"scylla-manager"* ]]; then
	for a in $tmp_base/${x86_arch} ; do createrepo -v --deltas $a/ ; done
else
	for a in $tmp_base/{$x86_arch,$all_archs} ; do createrepo -v --deltas $a/ ; done
fi

# Note that the following $basearch will be replaced by OS on installation
cat << EOF >> tmp_repo/${repo_name}.repo
[${repo_name}]
name=Scylla for Centos \$releasever - \$basearch
baseurl=http://${s3_repo_path_prefix}${repo_name}/\$basearch/
enabled=1
gpgcheck=0

EOF

if [[ $artifacts_prefix != *"manager"* ]]; then
  cat << EOF >> tmp_repo/${repo_name}.repo
[scylla-generic]
name=Scylla for centos \$releasever
baseurl=http://${s3_repo_path_prefix}${repo_name}/$all_archs/
enabled=1
gpgcheck=0

EOF
fi

echo "$0 ended successfully"
