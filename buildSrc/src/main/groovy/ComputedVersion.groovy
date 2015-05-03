/*
 * We determine the product version from the collection
 * of tags and branches and our current active branch.
 * Essentially, the versions implied by the branch and tag
 * names define an "occupied space", and the goal is to
 * find the next "free" version available that fits the
 * current branch name and is not occupied by a tag or
 * a more precise branch name.
 *
 * Version strings used here are 4 component "semantic" versions:
 *    major.minor.patch.hotfix
 *
 * Branching is assumed to be as follows:
 *
 *   master (latest and greatest) ____________________________________
 *                                 \                         \
 *   patch                          \_______________1.0.next  \____1.1.next ...
 *                                    \            \            \
 *   hotfix                            \_1.0.0.next \_1.0.1.next \_1.1.0.next ...
 *                                       |       |           |           |
 *   tagged releases                   v1.0GA v1.0.0.1GA  v1.0.1GA     v1.1GA
 *
 * In this example, a build on a branch results in the following versions:
 *
 *     master     ->  1.2     (because 1.1 is taken by 1.1.next)
 *     1.0.next   ->  1.0.2   (because 1.0.1 is taken by 1.0.1.next)
 *     1.0.0.next ->  1.0.0.2 (because 1.0 and 1.0.0.1 are already tagged)
 *     1.1.next   ->  1.1.1   (because 1.1 is taken by 1.1.0.next)
 *     1.1.0.next ->  1.1.0.1 (because 1.1 is already tagged)
 *
 * Note that bumping the major version requires changing the constant MASTER_VERSION below
 * Altenatively, one can drop the use of master altogether and use 1.next, 2.next instead.
 */
 
import org.gradle.api.GradleException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.LogCommand
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.revwalk.RevCommit;

/*
 * JGit wrapper class to implement build related git functions.
 */

class ComputedVersionGit {
    private Repository repo = null

    // Default constructor
    ComputedVersionGit() {
        repo = (new FileRepositoryBuilder())
                .readEnvironment()
                .findGitDir()
                .build()
    }

    // Constructor needed for things to work inside IntelliJ. For some reason,
    // IntelliJ switches the current directory to someplace outside the git repo,
    // so we need to find our way back. The top level gradle script knows...
    ComputedVersionGit(String rootDir) {
        repo = (new FileRepositoryBuilder())
                .setGitDir(new File(rootDir+'/.git'))
                .setWorkTree(new File(rootDir))
                .build()
    }

    /**
     * Locate all remote branches
     * @return List of branches, stripped of the refs/remotes/ prefix
     */
    List<String> getBranches() {
        return (new Git(repo)).branchList().setListMode(ListMode.REMOTE).call().collect {
            it.getName()
        }
    }

    /**
     * Locate all tags
     * @return List of tags
     */
    List<String> getTags() {
        return new Git(repo).tagList().call().collect {
            it.getName()
        }
    }

    /**
     * Compute the number of commits in localBranch which are not
     * in remoteBranch.
     * @return number of commits in localBranch not in remoteBranch
     */
    Integer getDistanceBetween(String remoteBranch, String localBranch, Integer maxCount) {
        Integer distance = 0
        // TODO: hook this into the Gradle logger. This is not as simple as it appears,
        // because the gradle logger only works in a project or a task context. This will
        // be taken care of in a future changeset.
        //println("Distance between ${remoteBranch} and ${localBranch} is ${distance}")
        LogCommand git = (new Git(repo)).log()
                .addRange(repo.resolve(remoteBranch), repo.resolve(localBranch))
        if (maxCount) {
            git = git.setMaxCount(maxCount)
        }
        git.call().each { distance += 1 }
        // TODO: see above.
        //println("Distance between ${remoteBranch} and ${localBranch} is ${distance}")
        return distance
    }

    /**
     * Given a list of remote branches and a local branch, find the closest
     * remote branch
     * @return closest remote branch
     */
    String findClosestRemoteBranch(List<String> remoteBranches, String localBranch) {
        // First check for direct hit
        if (remoteBranches.contains(localBranch)) {
            return localBranch
        }

        // Now check for minimum
        Integer minDistance = null
        Integer maxCount = null
        String minBranch = null
        remoteBranches.each { remoteBranch ->
            Integer distance = getDistanceBetween(remoteBranch, localBranch, maxCount)
            // We go from oldest (master) to newest. It might be desirable to go the other
            // way, but master being by far the most common use case, it's more effective
            // to optimize for it.
            if (minDistance == null || distance < minDistance) {
                minDistance = distance
                maxCount = distance + 1  // Limit log retrieval to current min to save time
                minBranch = remoteBranch
            }
        }
        return minBranch
    }

    /**
     * Determine active remote branch by determining the closest known
     * remote branch to the current HEAD.
     * @return active remote branch
     */
    String getActiveBranch(List<String> remoteBranches) {
        String branch = repo.getBranch() ?: 'HEAD'
        String trackingBranch = (new BranchConfig(repo.getConfig(), branch)).getTrackingBranch() ?: branch
        return findClosestRemoteBranch(remoteBranches, trackingBranch)
    }

    void close() {
        repo.close()
    }
}

class ComputedVersionComponent {
    // Define the node of a tree rooted at the major version,
    // and the tail being a map of lesser version numbers to
    // further nodes. We also track the highest version number
    // for that component. The next free version is max + 1

    private Integer max = -1
    private Map<Integer,ComputedVersionComponent> tail = [:]  // Empty map

    void addVersionComponentTuple(List<Integer> tuple) {
        if (tuple.size() > 0) {
            Integer head = tuple[0]
            if (!tail[head]) {
                tail[head] = new ComputedVersionComponent()
            }
            if (head > max) {
                max = head
            }
            if (tuple.size() > 1) {
                tail[head].addVersionComponentTuple(tuple[1..-1])  // [1..-1] is all elements except the first
            }
        }
    }

    List<Integer> getVersionComponentTuple(List<Integer> tuple) {
        Integer head = tuple[0]
        if (!tail[head]) {
            throw new GradleException("Attempting to retrieve a version outside the range of registered versions")
        }
        return ( tuple.size() > 1
                 ? [head]+tail[head].getVersionComponentTuple(tuple[1..-1])  // [1..-1] is all elements except the first
                 : [head]+[tail[head].max + 1] )
    }
}

class ComputedVersion {
    // Class holding the version tree for our current checkout
    // and the current active branch

    static List<String> MASTER_BRANCHES = [  // map of master branch names
            'refs/remotes/origin/master',
            'refs/remotes/origin/master-latest-build' ]
    static Integer MASTER_VERSION = 2  // master is 2.next - needs to be updated
                                       // as soon as we start working on 3.0

    // See http://groovy.codehaus.org/Documenting+Regular+Expressions+in+Groovy
    static String TAG_REGEX = '''(?x)        # Allow this format
                                 ^v          # always begins with "v"
                                 (\\d+)      # major  2nd group (the 1st group is always the whole expression)
                             (\\.(\\d+))?    # minor  4th group (the 3rd includes the dot)
                             (\\.(\\d+))?    # patch  6th...
                             (\\.(\\d+))?    # hotfix 8th...
                                 GA$         # must end in GA'''

    static String BRANCH_REGEX = '''(?x)     # Allow this format
                                    ^.*/     # remote branch
                                    (\\d+)   # major
                                (\\.(\\d+))? # minor (optional)
                                (\\.(\\d+))? # patch (optional)
                                \\.next$     # must end in .next'''

    String activeBranch = null

    private ComputedVersionComponent versions = new ComputedVersionComponent()
    private List<List<Integer>> remoteBranches = []

    private List<Integer> makeTuple(String string, String matchRegex) {
        Integer matchCount = 0
        List<Integer> tuple = []
        string.findAll(~matchRegex) { match ->
            match.each {
                matchCount += 1
                // We assume the "matcher" regexp is of the form
                // ^prefix(\d+)(\.(\d+))?(\.(\d+))?(\.(\d+))?suffix$
                // so only even numbered matches contain version numbers.
                if (matchCount % 2 == 0 && it != null) {
                    tuple << it.toInteger()
                }
            }
        }
        return tuple
    }

    private List<Integer> makeTupleFromBranch(String branch) {
        return ( MASTER_BRANCHES.contains(branch)
                 ? [MASTER_VERSION]
                 : makeTuple(branch, BRANCH_REGEX) )
    }

    private String makeBranchFromTuple(List<Integer> tuple) {
        return ( tuple
                 ? 'refs/remotes/origin/' + tuple.join('.') + '.next'
                 : MASTER_BRANCHES[0] )
    }

    private List<Integer> makeTupleFromTag(String tag) {
        return makeTuple(tag, TAG_REGEX)
    }

    private void addBranch(String branch) {
        List<Integer> tuple = makeTupleFromBranch(branch)
        if (tuple) {
            versions.addVersionComponentTuple(tuple)
            // Master is weird: on the one hand, it could be 4.next, but
            // for sorting purposes, it is the oldest branch ever, so we
            // assign it the empty tuple instead of [MASTER_VERSION]
            // If this is not done, then 3.9.next would be older than 4.next (aka master)
            // which is wrong. A feature branch created off of master
            // before 3.9.next was created would then get assigned 3.9.next,
            // when it should be staying with master.
            remoteBranches << ( MASTER_BRANCHES.contains(branch) ? [] : tuple )
        }
    }

    private void addTag(String tag) {
        List<Integer> tuple = makeTupleFromTag(tag)
        if (tuple) {
            versions.addVersionComponentTuple(tuple)
        }
    }

    private List<Integer> getVersionTuple(List<Integer> tuple) {
        try {
            List<Integer> result = versions.getVersionComponentTuple(tuple)
            while (result[-1] == 0) {  // [-1] = last element
                result.pop()
            }
            return result
        } catch(e) {
            // catch and rethrow, so we can add the original tuple into the
            // exception. And yes, if for some reason we cannot determine the
            // product version, we need to fail the build.
            throw new GradleException("Version outside of known range: ${tuple}")
        }
    }

    private void seedBranchAndTags(String rootDir) {
        ComputedVersionGit git = new ComputedVersionGit(rootDir)
        git.getTags().each { addTag(it) }
        git.getBranches().each { addBranch(it) }

        // Sort by age. 1.0.next is created before 1.0.1.next etc..
        // master is the empty list and is the oldest of them all.
        // The sorting helps resolve ties when determining the closest
        // remote branch
        remoteBranches.sort {
            if (it) {
                // Sadly, groovy has a weird rule for sorting tuples:
                // [1,3] < [1,4], but [1,3] > [4]. This means one
                // has to ensure we are comparing same sized tuples.
                List<Integer> padded = it.collect()
                while (padded.size() < 4) {
                    padded << 0  // append zeros until we are good.
                }
                return padded
            } else {
                return []
            }
        }

        activeBranch = git.getActiveBranch(remoteBranches.collect { makeBranchFromTuple(it) })
        git.close()
    }

    /**
     * Compute the version string to use on the current git branch
     * @return the unpadded version string (no trailing zeros)
     */
    String getVersion(String rootDir) {
        seedBranchAndTags(rootDir)
        String version = 'unknown'
        if (activeBranch) {
            version = getVersionTuple(makeTupleFromBranch(activeBranch)).join('.')
        }
        return version
    }

    /**
     * Pad a version string with zeros to make a 4 component version
     * @return a version string of the form "major.minor.patch.hotfix"
     */
    String padVersion(String version) {
        List<String> tuple = version.tokenize('.')  // split() adds regex overhead
        while (tuple.size() < 4) {
            tuple << '0'
        }
        return tuple.join('.')
    }

    void testMe() {
        [ 'v1.8.0.1GA',
          'v1.8.0.3GA',
          'v1.8.1.1GA',
          'v1.8.2.1GA' ].each { addTag(it) }
        [ 'refs/remotes/origin/1.8.0.next',
          'refs/remotes/origin/1.8.1.next',
          'refs/remotes/origin/1.8.next',
          'refs/remotes/origin/1.9.next',
          'refs/remotes/origin/master',
          'refs/remotes/origin/1.next' ].each { addBranch(it) }
        [ 'refs/remotes/origin/1.next':     '1.10',
          'refs/remotes/origin/master':     "${MASTER_VERSION}",
          'refs/remotes/origin/1.8.next':   '1.8.3',
          'refs/remotes/origin/1.9.next':   '1.9',
          'refs/remotes/origin/1.8.0.next': '1.8.0.4',
          'refs/remotes/origin/1.8.1.next': '1.8.1.2' ].each { testCase, expectedResult ->
             String actualResult = getVersionTuple(makeTupleFromBranch(testCase)).join('.')
             println("${testCase} -> ${actualResult} (expected: ${expectedResult})")
             assert(actualResult == expectedResult)
        }
    }
}
