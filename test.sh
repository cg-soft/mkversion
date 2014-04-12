#!/bin/sh

# The following construct works on both MacOS and CentOS
thisFile="$(readlink "$0" || echo "$0")"
here="$(cd "$(dirname "$thisFile")" && pwd)"

cp /dev/null "$here/actual_output.txt"
while read input
do
  echo "$input ->"\
       `"$here/mkversion.py" "$here/sample_tags.txt" "$here/sample_branches.txt" $input`\
     >> "$here/actual_output.txt"
done < "$here/test_cases.txt"

diff "$here/golden_output.txt" "$here/actual_output.txt"
