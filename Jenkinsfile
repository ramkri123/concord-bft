node {
    // We use the "library" step here instead of the @Library directive at the
    // top of the file because we want to load the Builder class from whatever
    // the current branch is, and @Library requires that it be hard coded.
    buildLib = library("SharedJenkinsLib@${BRANCH_NAME}").org.vmware
    builder = buildLib.Builder.new(this)
    builder.startBuild()
}
