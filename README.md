# Version Number Generator

Most projects expect the version to be manipulated by hand,
usually by editing a properties file, or some build script.

Some projects require carefully coordinated editing of multiple
locations, and at the right time in the release process.

This gradle code is an example on how one can avoid
doing this, and instead derive the project version from the git
branches and tags in use.

An important side effect of using computed version strings is that
one avoids merge conflicts when propagating changes from one
branch to another. 

A more important aspect is that we revert to an "atomic" change
model. The simple act of creating a tag or a branch will automatically
cause the right things to happen, without any additional manual steps.

## Version Model

This script assumes [semantic versioning](http://semver.org/), applied
to a four component version string:

* Major
* Minor
* Patch
* Hotfix

## Build Process Assumptions

In spite of best efforts, most builds are not truly reproducible. So building
a process around the idea that any build can be reproduced exactly is bound 
to lead to misery.

It turns out that in practice, it is best to assume that [builds are not reproducible](http://blog.fortified-bikesheds.com/2011/12/how-important-are-reproducible-builds.html). Instead, pretend every single build is your release build, and then simply make a determination by examining and testing the binaries whether you actually wish to release or deploy that build.

In this context, there is no room for maven SNAPSHOT versioning, or the common "pre-tagging" of builds. Instead, it is important that any build made at any time is releasable.

## Tagging model

The assumption is that git tags are applied at the end of the release process,
once a final detemination of the source set has been made. 

For the purposes of this script, tags are assumped to be of the form:

> **v**_major_[._minor_[._patch_[._hotfix_]]]**GA**

For example: v1.2GA, v0.5.1GA are all good tags.

Once a tag is applied, it sticks and is not ever expected to be moved. Tags should document what has happened, as opposed to expressing intent. That's what branches are for.

## Branching Model

The branches express the intent to deliver a specific version. That version is expressed in the branch name. Generally, there are two basic ways to do this:

* Branch for a specific version, then branch again for the next version
* Branch for an ongoing series, and branch again only for patches and hotfixes.

The latter model is usually preferred, as it generally requires less disruption of developer workflow. If a developer misses a particular deadline for a specific version, no reconfiguring is required as the same branch is used for the next version. Reconfiguring is only required if a patch is to be delivered.

The two models are expressed in the naming convention as follows:

* A branch for a specific version is simply named for that version. For example: 0.5.1, 1.8 ...
* A branch for a series of versions is expressed by appending ".next" to the branch name. For example: 1.next is used for 1.1, 1.2, 1.3 etc..., but not for 2.2, and also not for 1.1.2 or such.

The gradle script here only deals with the latter model. It is easy to add code to deal with the former model.

## Example

        master (latest and greatest) ____________________________________
                                      \                         \
        patch                          \_______________1.0.next  \____1.1.next ...
                                         \            \            \
        hotfix                            \_1.0.0.next \_1.0.1.next \_1.1.0.next ...
                                            |       |           |           |
        tagged releases                   v1.0GA v1.0.0.1GA  v1.0.1GA     v1.1GA
 
In this example, a build on a branch results in the following versions:

        master     ->  1.2     (because 1.1 is taken by 1.1.next)
        1.0.next   ->  1.0.2   (because 1.0.1 is taken by 1.0.1.next)
        1.0.0.next ->  1.0.0.2 (because 1.0 and 1.0.0.1 are already tagged)
        1.1.next   ->  1.1.1   (because 1.1 is taken by 1.1.0.next)
        1.1.0.next ->  1.1.0.1 (because 1.1 is already tagged)
 
Note that bumping the major version requires changing the constant `MASTER_VERSION` below. Altenatively, one can drop the use of master altogether and use 1.next, 2.next instead.

## How is the Next Version Determined?

Given the current branch, and the set of branch and tag names known in git, we compute the project version as follows:

* The version string will start with the explicit components mentioned in the current branch.
* The ".next" suffix will be replaced by a number one higher than the largest number used as the explicit portion of the version in any other branch or tag. If there is no other branch or tag with the same prefix as the current branch, then the suffix will be zero.
* The treatment of the "master" branch can be arbitrary. A constant in the script can be set to map the "master" branch to a string which conforms to the conventions above - or you can choose to not use master for any release builds.

## Testing

* Clone this repository
* Ensure you have gradle version 2.1 or later installed
* run "gradle testComputedVersion" from the top.
