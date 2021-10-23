#!/bin/bash

set -e

PROGRAM=$(basename $0)
DIR=$(dirname $(readlink -f $0))
dry_run=false
repeat_tests=1
testing_nose=true
NOSE_FLAGS=""
PYTEST_FLAGS=""
source $DIR/sh-utils.sh

function usage {
  echo "Usage: $PROGRAM --mode={release|debug|dev} --smp=<n> --home=<path> [--test_runner={nosetest|pytest] [--include=<tests>] [--exclude=<tests>] [--random=<n>|all [--random_seed=<seed>]] [--scylla_ext_opts=<flags>] [--scylla_ext_env=<vars>] [--debug] [--keep_logs] [--repeat=<num>] [--dry_run]"
  echo ""
  echo "    --mode={release|debug|dev} Build mode."
  echo "    --smp=<n> Number of processors to use when launching Scylla, Usually 1 or 2."
  echo "    --home=<path> Path that overrides the HOME environment variable, usually the scylla dir under dtest dir on workspac."
  echo "    [--test_runner={nosetest|pytest}; default=nosetest] Test runner."
  echo "    [--include=<(test-1 test-n)|(test-attribute-1, test-attribute-n)>] Comma separated list of tests attributes and/or space separated list of tests to include in the dtest run"
  echo "    [--exclude=<(test-1 test-n)|(test-attribute-1, test-attribute-n)>] Comma separated list of tests attributes and/or space separated list of tests to exclude in the dtest run"
  echo "    [--random=<n>|all>; default='all'] Number of random tests to run. \"all\" for shuffling all tests"
  echo "    [--random_seed=<seed>] a random seed for reproducing a run of random dtests"
  echo "    [--scylla_ext_opts=(flag-1 flag-n)] Space separated list of command line options and values. Example: \"--abort-on-seastar-bad-alloc --abort-on-lsa-bad-alloc=1\""
  echo "    [--scylla_ext_env=(env-var1=value; env-varn=value)] Semicolon separated list of env vars and values. Example: \"ASAN_OPTIONS=disable_coredump=0,abort_on_error=1;UBSAN_OPTIONS=halt_on_error=1:abort_on_error=1;BOOST_TEST_CATCH_SYSTEM_ERRORS=no\""
  echo "    [--debug] to enable dtest debugging."
  echo "    [--keep_logs] to keep logs."
  echo "    [--repeat=<n>; default=1] How many times to repeat the tests."
  echo "    [--dry_run] To print commands instead of running them."
  exit 1
}

function kill_nosetests () {
	# Temp solution to prevent timeouts
	echo "Killing any remaining nosetests processes"
	ps aux | grep [n]osetests
	if [ $? -eq 0 ]
   	then
		pkill -9 nosetests
		echo "After kill:"
		ps aux | grep [n]osetests
	else
		echo "No nosetests processes found. Nothing to kill"
	fi
}

function cleanup_workspace {
  set +e
  # Usually we clean the work space, so these files should not exist.
  # but we support keeping the work space for debug, so we still need these.
  sudo rm -Rf $HOME/.dtest
  sudo rm -Rf $HOME/.ccm
  sudo rm -Rf logs
  sudo rm -Rf logs-$dtest_type.$mode.$NODE_INDEX
  mkdir logs-$dtest_type.$mode.$NODE_INDEX
  rm -Rf ../scylla-dtest.$dtest_type.$mode.$NODE_INDEX.xml
  if $testing_nose ; then
    kill_nosetests
  fi
  set -e
}

function setup_environment_vars {
  # The env script assums we run under scylla-dtest, and that ccm dir is next to it (sister)
  echo "Path before setting: |$PATH|"
  source scylla_dtest_env.sh
  echo "Path after setting: |$PATH|"
  echo "Locale settings:"
  echo "LC_ALL=\"$LC_ALL\""
  echo "LANG=\"$LANG\""
  echo "LANGUAGE=\"$LANGUAGE\""
  if $testing_nose ; then
    echo "Value after running scylla_dtest_env script: CASSANDRA_DIR=\"$CASSANDRA_DIR\""
    # Overide CASSANDRA_DIR set by scylla_dtest_env.sh - add mode
    export CASSANDRA_DIR=`pwd`/../scylla/build/$mode/
    echo "Value after over-ride: CASSANDRA_DIR=\"$CASSANDRA_DIR\""
  fi
  export LOG_SAVED_DIR=`pwd`/logs-$dtest_type.$mode.$NODE_INDEX
  echo "Env settings done"
}

function setup_nose_virtualenv {
  echo "Install dependencies for nose:"
  # Add dnf clean as a workaround for dnf install problem that prevents packages from being installed in some cases.
  sudo dnf clean all
  sudo ./install-dependencies.sh

  python_version=$(python -V 2>&1)
  python_path=$(which python)
  echo "Python version: ${python_version}, installed on ${python_path}"

  echo "Build a virtualenv:"
  VENV_ROOT='.dtest_venv'
  rm -rf $VENV_ROOT

  ./scripts/setup-virtualenv --virtualenv-root=$VENV_ROOT --python="$python_path"

  echo "Activating by running |source ${VENV_ROOT}/bin/activate|"
  source $VENV_ROOT/bin/activate
  echo "Activating done"
}

function min() {
    local ret="$1"; shift
    while (( $# )); do
	curr="$1"
	ret=$((curr < ret ? curr : ret)); shift
    done
    echo "$ret"
}

function test_processes() {
    local smp="$1"
    local nodes_per_cluster="$2"
    local mb_per_cpu="$3"

    local nproc="$(nproc)"
    local cpu_per_dtest=$(($smp * $nodes_per_cluster))
    local cpu_limit=$((nproc / cpu_per_dtest))

    local total_mem=$(($(getconf _PHYS_PAGES) * $(getconf PAGESIZE)))
    local mem_per_dtest="$((cpu_per_dtest * mb_per_cpu * 1024 * 1024))"
    local mem_limit=$((total_mem / mem_per_dtest))

    local mode_limit=10
    if [[ "$mode" == "debug" ]]; then
        mode_limit=3
    fi
    local limit=$(min $mode_limit $cpu_limit $mem_limit)
    limit=$((limit < 1 ? 1 : limit))
    echo "$limit"
}

function collect_nose_tests_list() {
  tmp_file="/tmp/collect_nose_tests_list.$(date +%s).out"
  $RUN_TEST_CMD --collect-only "$@" >& $tmp_file
  status=$?
  if [ $status != 0 ]; then
    echo "Collecting tests failed: status=$status"
    echo "Output:"
    cat $tmp_file
    echo "-------------------"
    exit $status
  fi 1>&2
  awk '/^Failure:/ {next}
           /\.\.\. ok$/ {
               t = $1;
               ms = $2;
               gsub(/[()]/, "", ms);
               n = split(ms, a, /[.]/);
               f = a[1];
               for (i = 2; i < n; i++) {
                   f = f "/" a[i];
               }
               m=a[n];
               printf("%s.py:%s.%s\n", f, m, t);
           }' $tmp_file
  rm -f $tmp_file
}

function copy_orphaned_logs() {
	local src
	local src_node
	for src in $HOME/.dtest/dtest-*; do
		local t=""
		if [ -f "$src/test/current_test" ]; then
			local name="$src/test/current_test"
			ts=$({ echo "import os"; echo "print(int(os.path.getctime('$name')))"; } | python)
			t=${ts}_$(cat "$name")
		fi
		if [ -z "$t" ]; then
			t=$(basename "$src")
		fi
		run_cmd mkdir -p "$LOG_SAVED_DIR/orphaned/$t"
		echo Copying logs from orphaned dtest $src to $LOG_SAVED_DIR/orphaned/$t
		for src_node in "$src"/test/node*; do
			local n=$(basename "$src_node")
			run_cmd cp "$src_node/logs/system.log" "$LOG_SAVED_DIR/orphaned/$t/$n.log"
		done
		run_cmd rm -rf "$src"
	done
}

for i in "$@"
do
case $i in
    --home*)
    home_dir="${i#*=}"
    shift
    ;;
    --mode*)
    mode="${i#*=}"
    shift
    ;;
    --smp*)
    smp="${i#*=}"
    shift
    ;;
    --test_runner*)
    test_runner="${i#*=}"
    shift
    ;;
    --include*)
    tests="${i#*=}"
    shift
    ;;
    --exclude*)
    excluded_tests="${i#*=}"
    shift
    ;;
    --random=*)
    random="${i#*=}"
    shift
    ;;
    --random_seed*)
    random_seed="${i#*=}"
    shift
    ;;
    --debug*)
    set_dtest_debug=true
    shift
    ;;
    --keep_logs*)
    keep_logs=true
    shift
    ;;
    --scylla_ext_opts*)
    scylla_ext_opts_param="${i#*=}"
    shift
    ;;
    --scylla_ext_env*)
    scylla_ext_env_param="${i#*=}"
    shift
    ;;
    --repeat*)
    repeat_tests="${i#*=}"
    shift
    ;;
    --dtest-type*)
    dtest_type="${i#*=}"
    shift
    ;;
    --dry_run)
    dry_run=true
    shift
    ;;
    *)
    echo "Error: unknown command line option: |$i|"
    usage
    exit 1
    ;;
esac
done

fail_if_param_missing "$mode" '--mode'
fail_if_param_missing "$smp" '--smp'
fail_if_param_missing "$home_dir" '--home'

echo "$PROGRAM got these Parameters:"
echo "   --home            = \"$home_dir\""
echo "   --mode            = \"$mode\""
echo "   --smp             = \"$smp\""
echo "   --test_runner     = \"$test_runner\""
echo "   --dry_run         = \"$dry_run\""
echo "   --debug           = \"$set_dtest_debug\""
echo "   --exclude         = \"$excluded_tests\""
echo "   --include         = \"$tests\""
echo "   --random          = \"$random\""
echo "   --dtest_type      = \"$dtest_type\""
echo "   --random_seed     = \"$random_seed\""
echo "   --keep_logs       = \"$keep_logs\""
echo "   --scylla_ext_opts = \"$scylla_ext_opts_param\""
echo "   --scylla_ext_env  = \"$scylla_ext_env_param\""
echo "=================="

if [[ "$test_runner" = "pytest" ]]; then
  testing_nose=false
fi

# Script is called on with workspace as the current directory, which contains the scylla, scylla-ccm, scylla-dtest, and other directories.
if [ -h "scylla/resources/cassandra" ]; then
	rm scylla/resources/cassandra
fi
mkdir -p scylla/resources

scylla_tools_java_dir="`pwd`/scylla/tools/java"

ln -s $scylla_tools_java_dir scylla/resources/cassandra

cd scylla-dtest

cleanup_workspace

setup_environment_vars
reloc_package_name="scylla-package.tar.gz"
if [ -n $SCYLLA_CORE_PACKAGE_NAME ] ; then
  reloc_package_name="$SCYLLA_CORE_PACKAGE_NAME"
fi

if [ -z $SCYLLA_VERSION ] ; then
  echo "SCYLLA_VERSION is not defined. Setting ccm create only for cas-tmp"
  export INSTALL_CASSANDRA='ccm create cas-tmp --vnodes -n 1 --version=3.11.3'
else
  echo "SCYLLA_VERSION is defined: |${SCYLLA_VERSION}| Running ccm create scylla-tmp and cas-tmp"
  export INSTALL_CASSANDRA='ccm create cas-tmp --vnodes -n 1 --version=3.11.3; ccm create scylla-tmp --scylla -n 1 --version=${SCYLLA_VERSION}'
fi

RUN_TEST_CMD="./scripts/run_test.sh"
export TOOLS_JAVA_DIR="$scylla_tools_java_dir"

# Copy binaries to logs, before running tests, so it will be available even if we have timeout
run_cmd mkdir -p "$LOG_SAVED_DIR"
if [ ! -f $CASSANDRA_DIR/$reloc_package_name ] ; then
  if [ -e $CASSANDRA_DIR/scylla ] ; then
    run_cmd cp $CASSANDRA_DIR/scylla $LOG_SAVED_DIR/
  else
    echo "Scylla binary was not found, Can't backup it (to upload later as an artifact)"
  fi
else
  echo "No need to backup 'scylla' binary because '$reloc_package_name' already exists."
fi

if [ "x$tests" = "xgating" ]; then
	echo "Going to run next-gating dtest"
	export tests="-a next-gating"
	export all_modules_flag=true
elif [[ "$tests" == -a* ]]; then
	export all_modules_flag=true
elif [[ "$tests" == @* ]]; then
	export tests=$(cat ${tests:1})
else
	export all_modules_flag=false
	if [ "x$tests" == "x" ]; then
		echo "No specific tests were given."
		if [ "$mode" == "debug" -a -z "$random" ]; then
			export tests="-a dtest-debug"
		else
			export tests="`cat scylla_tests`"
		fi
	fi
	echo "Dtest tests before exclude:"
	echo $tests
	echo "====== End of Tests list before exclude ============="

	if [ "x$excluded_tests" != "x" ]; then
		echo "Excluding tests"
		export tests="$tests --exclude `echo $excluded_tests | sed s/' '/'\|'/g`"
	fi
fi

if [[ -n "$random" ]]; then
  if $testing_nose ; then
    echo "Selecting $random random tests"
    if [[ -z "$random_seed" ]]; then
      random_seed=$(($(date +%s)*32768 + $$))
    fi
    echo "random_seed=$random_seed"
    export tests=$(collect_nose_tests_list -a '!dtest-debug,!dtest-heavy' $tests | shuffle "$random")
    echo "Selected tests: $tests"
    if [ -z "$tests" ]; then
      exit 1
    fi
  else
    if [[ ! -z "$random_seed" ]]; then
      PYTEST_FLAGS="${PYTEST_FLAGS} --randomly-seed=${random_seed}"
    else
      PYTEST_FLAGS="${PYTEST_FLAGS} -p no:randomly"
    fi
  fi
fi

if [ $repeat_tests -gt 1 ]; then
  echo "Repeating tests $repeat_tests times"
  if $testing_nose ; then
    if $(echo "$tests" | grep -qw -- -a); then
      echo "Going to create list of tests by |$tests|"
      tests=$(collect_nose_tests_list $tests)
    fi
    orig_tests="$tests"
    for i in $(seq 2 ${repeat_tests}); do
      export tests="${tests} ${orig_tests}"
    done
  else
    PYTEST_FLAGS="${PYTEST_FLAGS} --count ${repeat_tests}"
  fi
fi

echo "Going to run dtest tests:"
echo $tests
echo "====== End of Tests list ============="

if [ "x$set_dtest_debug" = "x" ]; then
	set_dtest_debug=false
fi


if [ "x$keep_logs" = "x" ]; then
	keep_logs=false
fi

echo "Setting env for dtest"
export HOME=$home_dir

export mb_per_cpu=512
export nodes_per_cluster=3

if $testing_nose ; then
  export REUSE_CLUSTER=false
  NOSE_FLAGS="$NOSE_FLAGS --process-timeout=7200 --process-restartworker --with-xunitmp --xunitmp-file=../scylla-dtest.$dtest_type.$mode.$NODE_INDEX.xml"
  export NOSE_PROCESSES=$(test_processes "$smp" "$nodes_per_cluster" "$mb_per_cpu")
else
  PYTEST_FLAGS="${PYTEST_FLAGS} -v --junit-xml=../scylla-dtest.$mode.$NODE_INDEX.xml"
  export XDIST_PROCESSES=$(test_processes "$smp" "$nodes_per_cluster" "$mb_per_cpu")
  PYTEST_FLAGS="${PYTEST_FLAGS} -n ${XDIST_PROCESSES}"

  if [ -z "$SCYLLA_VERSION" ]; then
    PYTEST_FLAGS="$PYTEST_FLAGS --cassandra-dir=${CASSANDRA_DIR}"
  else
    PYTEST_FLAGS="$PYTEST_FLAGS --scylla-version=${SCYLLA_VERSION}"
  fi
fi


export SCYLLA_EXT_OPTS="--smp $smp --memory $(($smp * $mb_per_cpu))M $scylla_ext_opts_param"
if [[ "$SCYLLA_EXT_OPTS" != *--abort-on-internal-error* ]]; then
    export SCYLLA_EXT_OPTS="$SCYLLA_EXT_OPTS --abort-on-internal-error 1"
fi

export SCYLLA_EXT_ENV="$scylla_ext_env_param"
if [ "$mode" == "debug" -a -z "$SCYLLA_EXT_ENV" ]; then
    export SCYLLA_EXT_ENV="ASAN_OPTIONS=disable_coredump=0:abort_on_error=1;UBSAN_OPTIONS=halt_on_error=1:abort_on_error=1;BOOST_TEST_CATCH_SYSTEM_ERRORS=no"
fi

# Following 2 env vars are for debug. https://github.com/scylladb/scylla/wiki/Scylla-DTEST#additional-good-to-know
if $set_dtest_debug ; then
  if $testing_nose ; then
	  export PRINT_DEBUG=true
	  export DEBUG=true
    if $keep_logs ; then
    	export KEEP_LOGS=true
    fi
    if $all_modules_flag; then
    	NOSE_FLAGS="$NOSE_FLAGS --all-modules"
    fi

    if $dry_run ; then
    	export DRY_RUN=true
    fi
    echo "NOSE_FLAGS=\"$NOSE_FLAGS\""
    echo "NOSE_PROCESSES=\"$NOSE_PROCESSES\""
  else
    PYTEST_FLAGS="$PYTEST_FLAGS --log-cli-level=debug"
    if $dry_run ; then
      PYTEST_FLAGS="$PYTEST_FLAGS --collect-only"
    fi
    echo "PYTEST_FLAGS=\"$PYTEST_FLAGS\""
    echo "XDIST_PROCESSES=\"$XDIST_PROCESSES\""
  fi
fi

export KEEP_CORES=true

echo "SCYLLA_EXT_OPTS=\"$SCYLLA_EXT_OPTS\""
echo "SCYLLA_EXT_ENV=\"$SCYLLA_EXT_ENV\""
echo "============= Environment ============="
env
echo "========= End of Environment ============="
echo "Dont fail because tests returned an error, collect the logs in any case"
set +e

core_pattern="%e.%p.%t.core"
last_core_pattern=$(sudo /sbin/sysctl -n kernel.core_pattern)
if [ "$last_core_pattern" != "$core_pattern" ]; then
	echo "Setting kernel.core_pattern"
	sudo /sbin/sysctl kernel.core_pattern="$core_pattern"
fi

# Running this without the run_cmd function, as on dry-run it uses the DRY_RUN env var.
if $testing_nose ; then
  echo "Ruuning dtest: |$RUN_TEST_CMD $tests $NOSE_FLAGS|"
  $RUN_TEST_CMD $tests $NOSE_FLAGS
  exitStatus=$?
else
  echo "Running dtest: |$RUN_TEST_CMD $tests $PYTEST_FLAGS|"
  $RUN_TEST_CMD $tests $PYTEST_FLAGS
  exitStatus=$?
fi
echo "Tests ended with status |$exitStatus|"
if $dry_run ; then
  echo "Setting status to 0 if this is dry_run. Workaround till https://github.com/scylladb/scylla-dtest/issues/1137 is resolved"
  exitStatus=0
fi
if [ "$last_core_pattern" != "$core_pattern" ]; then
	echo "Restoring kernel.core_pattern"
	sudo /sbin/sysctl kernel.core_pattern="$last_core_pattern"
fi

if $testing_nose ; then
  kill_nosetests
fi

# Fix results file - hack for now https://issues.jenkins-ci.org/browse/JENKINS-51914
run_cmd sed -i s/skip=/skipped=/g ../scylla-dtest.$dtest_type.$mode.$NODE_INDEX.xml

# When all dtest pass, no logs are available. Don't fail build in this nice case.

# Copy any orphaned logs - workaround for https://github.com/scylladb/scylla-pkg/issues/198
if [ $(find $HOME/.dtest -maxdepth 1 -name 'dtest-*' -type d | wc -l) -ne 0 ]; then
	copy_orphaned_logs
fi

ls -la logs-$dtest_type.$mode.$NODE_INDEX

exit $exitStatus
