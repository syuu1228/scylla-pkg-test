#!/usr/bin/env bash

if [ ! -d poc-env ]; then
    pip3 install virtualenv
    python3 -m virtualenv poc-venv
fi
source poc-venv/bin/activate && pip3 install invoke pydantic requests
poc-venv/bin/invoke "$@"
