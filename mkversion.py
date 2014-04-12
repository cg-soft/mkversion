#!/usr/bin/env python

import sys
import re
import subprocess

# Set this to the version assumed by "master"
DEFAULT_MASTER_VERSION = (3, None, None, None)

# Valid tags look like this: v1GA, v1.2GA, v1.2.3GA or v1.2.3.4GA
tag_regexp = re.compile(r'^v(\d+)(\.(\d+)|)(\.(\d+)|)(\.(\d+)|)(GA)$')

# Valid branches look like this: 1.next, 1.2, 1.2.x ... 1.2.3-patch or 1.2.3.4
branch_regexp = re.compile(r'^.*/(\d+)(\.(\d+)|)(\.(\d+)|)(\.(\d+)|)(\.x|\.next|-patch|)$')

# This dict records whether there was a ".next" at the end
is_dev_branch = {}

# .. and this registers whether it was a GA tag
is_ga_tag = {}

class GitQuery:
    def __init__(self, git='git'):
        self.git_command = git

    def git(self, *args):
        cmd = [self.git_command,]
        cmd.extend(args)
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, stderr = p.communicate()
        if p.returncode != 0:
            print >>sys.stderr, "Error executing %r:\n" % cmd, stderr
            sys.exit(1)
        return stdout

    def tags(self):
        return self.git('tag').split("\n")

    def branches(self):
        my_branch = None
        branches = []
        for branch in self.git('branch', '--all').split("\n"):
            if branch.startswith('*'):
                branch = branch.lstrip('*').strip()
                my_branch = branch
            elif "/" in branch:
                # only register remote branches
                branches.append(branch)
        if my_branch is not None:
            # Figure out our tracked branch
            my_branch = self.git('rev-parse', '--abbrev-ref', my_branch+'@{upstream}').strip()
        return branches, my_branch

def process(m):
    """ Processes a regexp match object. We note that the odd group numbers
        (1,3,5,7) are our version components, so we build a tuple of it,
        and finally check if the last portion is ".next", so we mark that tuple
        as a development branch
    """
    if not m:
        return None
    i = 1
    result = []
    while i < 8:
        x = m.group(i)
        if x is None:
             result.append(None)
        else:
             result.append(int(x))
        i += 2
    result = tuple(result)
    if m.group(8) in ('-patch','.next','.x'):
        is_dev_branch[result] = True
    if m.group(8) == 'GA':
        is_ga_tag[result] = True
    return result

def is_ga(version):
    i = 3
    while i > 0:
        if version in is_ga_tag:
            return True
        if version[i] is not None and version[i] > 0:
            return False
        v = list(version)
        v[i] = None
        version = tuple(v)
        i -= 1
    return version in is_ga_tag

def is_same_base_version(ref, other):
    """ This compares the base portion of the version, and on matching
        returns a version with the tail portion incremented by one,
        unless the tail portion is also zero. So:
           ref=(1,2,0,0) other=(1,2,0,0) returns (1,2,0,0),
        but
           ref=(1,2,0,0) other=(1,2,1,0) returns (1,2,2,0),
        Exception is if we have a GA tag for the precise match, in which
        case we do create the .1 successor.
    """
    i = 0
    while i < 4:
        if ref[i] is None:
             version = list(ref)
             if other[i] is not None:
                 version[i] = other[i]+1
             elif is_ga(other):
                 version[i] = 1
             return tuple(version)
        if ref[i] != other[i]:
             return None
        i += 1

def display(version):
    """ Render a version string with tailing .0 components removed
    """
    sep = ''
    v = ''
    for n in version:
        if n is None:
             break
        if n == 0:
             sep += '0.'
        else:
             v += sep + str(n)
             sep = '.'
    return v

if len(sys.argv) > 1:
    # For testing 
    tags = open(sys.argv[1]).readlines()     # output of "git tag --list"
    branches = open(sys.argv[2]).readlines() # output of "git branch --all"
    my_branch_name = sys.argv[3] # Current branch
else:
    # For use in builds
    g = GitQuery()
    tags = g.tags()
    branches, my_branch_name = g.branches()

# Check if specified branch is valid, special case for "master"
if my_branch_name.endswith('/master'):
     my_branch = DEFAULT_MASTER_VERSION
     is_dev_branch[my_branch] = True
else:
     my_branch = process(branch_regexp.match(my_branch_name.strip()))
     if my_branch is None:
          print >>sys.stderr, "Bad branch:", my_branch_name
          sys.exit(1)

versions = []
# Process tags, we will need them
for tag in tags:
    v = process(tag_regexp.match(tag.strip()))
    if v is not None:
         versions.append(v)

# If our branch is not a ".next" branch, and there isn't a tag there, we're done.
# Arguably, once a tag is placed on a branch without ".next", a build there should be
# an error, since the tag effectively terminates any development on that branch.
if my_branch not in is_dev_branch and my_branch not in is_ga_tag:
    print display(my_branch)
    sys.exit(0)

# Now process branches
for branch in branches:
    v = process(branch_regexp.match(branch.strip()))
    if v is not None:
         versions.append(v)

# Now we walk through the sorted versions and find the highest
# version matching the given base version, and use the computed
# "next version" as our possible version string.
final_version = None
versions.sort()
for v in versions:
    candidate = is_same_base_version(my_branch, v)
    if candidate is None:
        if final_version is None:
            # We haven't found a match yet
            continue
        else:
            # we have stopped finding matches
            break
    # We found a possible match, contine checking...
    final_version = candidate

# If we don't find anything, we assume we have perfectly inserted ourselves into the
# middle of things or at the very end, so assume our branch name does specify the version.
if final_version is None:
    final_version = my_branch

print display(final_version)
