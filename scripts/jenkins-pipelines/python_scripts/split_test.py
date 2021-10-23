#!/usr/bin/env python3

import sys
import os
import logging
import argparse
import fnmatch
import pickle

from jenkinsapi.custom_exceptions import NoResults
from jenkinsapi.jenkins import Jenkins

def make_test_slices(test_data, slices, max_slice_duration):
    while test_data:
        current_test = test_data.pop(0)
        for current_slice in slices:
            if current_slice['total'] + float(current_test['duration']) > max_slice_duration:
                continue
            current_slice['total'] += float(current_test['duration'])
            current_slice['tests'] += ["%s.%s" % (current_test['className'], current_test['name'])]
            break
        else:
            slices += [dict(total=0.0, tests=[])]
            current_slice = slices[-1]
            current_slice['total'] += float(current_test['duration'])
            current_slice['tests'] += ["%s.%s" % (current_test['className'], current_test['name'])]

def fetch_test_duration(job_name, server, collected_test_list, test_data_dict):
    logging.info("scanning %s", job_name)
    res = server.get_job(job_name)
    for build_id in list(res.get_build_ids())[:5]:
        build = res.get_build(build_id)
        logging.info("scanning %s", build)
        if build.get_status() not in ['FAILURE', 'SUCCESS']:
            continue
        try:
            result_set = build.get_resultset()
        except NoResults:
            continue
        for suite in result_set._data.get("suites", []):
            for case in suite["cases"]:
                case_id = "%s.%s" % (case['className'], case['name'])
                if case_id in collected_test_list:
                    if case_id in test_data_dict:
                        # TODO: change from avg. to 95% median
                        test_data_dict[case_id]['duration'] = (float(test_data_dict[case_id]['duration']) + float(case['duration'])) / 2.0
                    else:
                        test_data_dict[case_id] = case

def clear_old_exclude_files(outputdir):
    logging.info("clear old exclude files")
    # Get a list of all files in directory
    for root_dir, sub_dir, filenames in os.walk(outputdir):
        # Find the files that matches the given patterm
        for filename in fnmatch.filter(filenames, "exclude_*.txt"):
            try:
                os.remove(os.path.join(root_dir, filename))
            except OSError:
                print("Error while deleting file")

        for filename in fnmatch.filter(filenames, "include_*.txt"):
            try:
                os.remove(os.path.join(root_dir, filename))
            except OSError:
                print("Error while deleting file")

def split_files_test_list (outputdir, slices, dtest_type):
    for i, current_slice in enumerate(slices):
        logging.info("%s: %ssecs - %d - %s", i, current_slice['total'], len(current_slice['tests']), current_slice['tests'])
        exclude_filename = os.path.join(outputdir, "exclude_%d.txt" % i)
        include_filename = os.path.join(outputdir, f"{dtest_type}-include_{i:03}.txt")

        with open(include_filename, 'w') as f:
            for case in current_slice['tests']:
                if case.startswith('cqlsh_tests'):
                    case = case.replace('.', '/', 1)
                case = case.replace('.', '.py:', 1)
                f.write(case + '\n')

        exclude_slices = slices[:i] + slices[i+1:]
        with open(exclude_filename, 'w') as f:
            for exclude_slice in exclude_slices:
                for case in exclude_slice['tests']:
                    f.write(case + '\n')

def split_tests(split_time, job_name, outputdir, collected_test_list, collected_test_list_ids, split_limit, dtest_type):

    server = Jenkins(os.environ['JENKINS_URL'], username=os.environ['JENKINS_TOKEN_USER'], password=os.environ['JENKINS_TOKEN_PASSWORD'], timeout=120)

    test_data_dict = {}
    if collected_test_list:
        collected_test_list = open(collected_test_list).read()
    elif collected_test_list_ids:
        collected_test_list = ""
        for _, class_name, test_name in pickle.load(open(collected_test_list_ids, 'rb'))['ids'].values():
            collected_test_list += "{}.{}\n".format(class_name, test_name)

    all_test_set = set([l.strip() for l in collected_test_list.strip().splitlines()])
    logging.info("%d tests collected from dtest", len(all_test_set))

    fetch_test_duration(job_name, server, collected_test_list, test_data_dict)

    tests_with_no_history = all_test_set - set(test_data_dict.keys())
    logging.info("%s test with no history test data", len(tests_with_no_history))
    for case in tests_with_no_history:
        logging.info(case)
        className, name = case.split('.', 1)
        test_data_dict[case] = dict(duration=2.0 * 60.0, name=name, className=className)

    test_data = list(test_data_dict.values())
    test_data.sort(key=lambda x: float(x['duration']), reverse=True)

    for i in test_data[:10]:
        logging.info("%ssecs %s", i['duration'], i['name'])

    slices = [dict(total=0.0, tests=[])]
    max_slice_duration = split_time * 60.0  # one hour

    make_test_slices(test_data, slices, max_slice_duration)

    clear_old_exclude_files(outputdir)

    split_files_test_list (outputdir, slices, dtest_type)

def main():
    logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')

    parser = argparse.ArgumentParser(description='split the run base on history from jenkins.')
    parser.add_argument('--split-time', type=float, required=True, help='what is the max duration of each split')

    parser.add_argument('--job-name', type=str, help="name of the job to scan it's test history")

    parser.add_argument('--outputdir', type=str, help='directory to generate the split exclude files',
                        default='.')

    parser.add_argument('--collected-test-list', type=str, help='list of test collected from nosetest',
                        default=None)

    parser.add_argument('--collected-test-list-ids', type=str, help='list of test collected from nosetest using --with-id',
                        default=None)

    parser.add_argument('--split-limit', type=int, help='max number of nodes to run splitted testing',
                        default=50)
                    
    parser.add_argument('--dtest-type', type=str, help='dtest type of test. full|long|heavy',
                        default=None)

    args = parser.parse_args()

    split_tests(args.split_time, args.job_name, args.outputdir, args.collected_test_list, args.collected_test_list_ids, args.split_limit, args.dtest_type)


if __name__ == "__main__":
    main()
