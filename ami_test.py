#!/usr/bin/env python3

import os
import sys
import re
import shutil
import argparse
from subprocess import run

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ami-id')
    args = parser.parse_args()

    print(args.ami_id)
