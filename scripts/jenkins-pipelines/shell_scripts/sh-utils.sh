#!/bin/bash
# run_cmd CMD
# Assume that DRY_RUN is defined to true or false
function run_cmd {
    CMD=$*

    if $DRY_RUN ; then
        echo "Dry-run: $CMD"
    else
	echo "Running: $CMD"
        $CMD
    fi
}

# fail_if_param_missing VALUE PARAM_NAME
function fail_if_param_missing {
  value=$1
  param=$2
  if [[ "x$value" = "x" ]]; then
    echo "Error: missing mandatory parameter '$param', exiting..."
    echo ""
    usage
    exit 1
  fi
}

# shuffle [N] [file ...]
#
# Shuffle lines of file(s) or standard input if none given.
# If N is given, print up to N results
function shuffle {
    limit="$(($1))" # make $1 into a number, possibly zero
    shift

    awk -v limit="$limit" '
        BEGIN {
            seed=systime() * 32768 + PROCINFO["pid"];
            srand(seed);
        }

        {
            a[count++] = $0
        }

        function nrand(n) {
            return int(n * rand())
        }

        function arr_shuffle(a, count, limit) {
            for (i = 0; i < limit; i++) {
                j = i + nrand(count - i)
                if (j != i) {
                    tmp = a[j]
                    a[j] = a[i]
                    a[i] = tmp
                }
            }
        }

        function arr_print(a, count) {
            for (i = 0; i < count; i++) {
                print a[i]
            }
        }

        END {
            if (!limit) {
                limit = count
            }
            arr_shuffle(a, count, limit)
            arr_print(a, limit)
        }
    ' "$@"
}

function split_scylla_version {
  full_version=$1
  IFS=. read major_version minor_version patch_version <<<"${full_version##*-}"
}


function add_trailing_slash_if_missing {
  URL=$1
  if [ ! -z "$URL" ] ; then
    length=${#URL}
    last_char=${URL:length-1:1}

    [[ $last_char != "/" ]] && URL="$URL/"; :
  fi
  echo "$URL"
}
